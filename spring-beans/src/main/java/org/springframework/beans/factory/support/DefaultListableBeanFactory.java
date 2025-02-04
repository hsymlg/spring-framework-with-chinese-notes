/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import jakarta.inject.Provider;

import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.core.OrderComparator;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.log.LogMessage;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.CompositeIterator;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Spring's default implementation of the {@link ConfigurableListableBeanFactory}
 * and {@link BeanDefinitionRegistry} interfaces: a full-fledged bean factory
 * based on bean definition metadata, extensible through post-processors.
 *
 * <p>Typical usage is registering all bean definitions first (possibly read
 * from a bean definition file), before accessing beans. Bean lookup by name
 * is therefore an inexpensive operation in a local bean definition table,
 * operating on pre-resolved bean definition metadata objects.
 *
 * <p>Note that readers for specific bean definition formats are typically
 * implemented separately rather than as bean factory subclasses: see for example
 * {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 *
 * <p>For an alternative implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} interface,
 * have a look at {@link StaticListableBeanFactory}, which manages existing
 * bean instances rather than creating new ones based on bean definitions.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 16 April 2001
 * @see #registerBeanDefinition
 * @see #addBeanPostProcessor
 * @see #getBean
 * @see #resolveDependency
 */
//ConfigurableListableBeanFactory和BeanDefinitionRegistry接口的Spring默认实现:
//一个基于bean定义元数据的成熟的bean工厂，可通过后处理器扩展。
//典型的用法是在访问bean之前先注册所有bean定义(可能从bean定义文件中读取)。
//因此，按名称查找是本地Bean定义表中的一种廉价操作，对预解析的Bean定义元数据对象进行操作。
//请注意，特定bean定义格式的阅读器通常是单独实现的，而不是作为bean工厂的子类实现的:参见示例
//PropertiesBeanDefinitionReader 和 org.springframework.beans.factory.xml.XmlBeanDefinitionReader.
//用于org.springframework.bean.factory.ListableBeanFactory接口的替代实现。看一下StaticListableBeanFactory，它管理现
//有的bean实例，而不是根据bean定义创建新的实例
@SuppressWarnings("serial")
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

	@Nullable
	private static Class<?> javaxInjectProviderClass;

	static {
		try {
			javaxInjectProviderClass =
					//使用当前类的类加载器加载javax.inject.Provider的class对象
					ClassUtils.forName("jakarta.inject.Provider", DefaultListableBeanFactory.class.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - Provider interface simply not supported then.
			//JSR-330 API不可用-那时根本不支持提供程序接口
			//加载不了时，让javaxInjectProviderClass置为null，以确保明确显示没有javax.inject.Provider的相关jar包
			javaxInjectProviderClass = null;
		}
	}


	/** Map from serialized id to factory instance. */
	//从反序列化的ID 映射到 工厂实例
	private static final Map<String, Reference<DefaultListableBeanFactory>> serializableFactories =
			new ConcurrentHashMap<>(8);

	/** Optional id for this factory, for serialization purposes. */
	@Nullable
	private String serializationId;

	/** Whether to allow re-registration of a different definition with the same name. */
	//是否允许重新注册具有相同名的不同定义
	private boolean allowBeanDefinitionOverriding = true;

	/** Whether to allow eager class loading even for lazy-init beans. */
	private boolean allowEagerClassLoading = true;

	/** Optional OrderComparator for dependency Lists and arrays. */
	//依赖项列表和数组的可选OrderComparator
	@Nullable
	private Comparator<Object> dependencyComparator;

	/** Resolver to use for checking if a bean definition is an autowire candidate. */
	//用于检查beanDefinition是否为自动装配候选的解析程序
	private AutowireCandidateResolver autowireCandidateResolver = SimpleAutowireCandidateResolver.INSTANCE;

	/**
	 * <p>存放着手动显示注册的依赖项类型-相应的自动装配值的缓存</p>
	 * <p>手动显示注册指直接调用{@link #registerResolvableDependency(Class, Object)}</p>
	 * Map from dependency type to corresponding autowired value.
	 * <p>从依赖项类型映射到相应的自动装配值</p>
	 * */
	private final Map<Class<?>, Object> resolvableDependencies = new ConcurrentHashMap<>(16);

	/** Map of bean definition objects, keyed by bean name. */
	//<p>Bean定义对象的映射，以Bean名称为键</p>
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

	/** Map from bean name to merged BeanDefinitionHolder. */
	private final Map<String, BeanDefinitionHolder> mergedBeanDefinitionHolders = new ConcurrentHashMap<>(256);

	/** Map of singleton and non-singleton bean names, keyed by dependency type. */
	//<p>单例和非单例Bean名称的映射，按依赖项类型进行键控</p>
	//<p>键控：在这里应该是指map的key/value形式的映射</p>
	private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<>(64);

	/** Map of singleton-only bean names, keyed by dependency type. */
	//<p>仅依赖单例的bean名称的映射，按依赖项类型进行键控</p>
	private final Map<Class<?>, String[]> singletonBeanNamesByType = new ConcurrentHashMap<>(64);

	/** List of bean definition names, in registration order. */
	//<p>BeanDefinition名称列表，按注册顺序，BeanDefinition名称与Bean名相同</p>
	private volatile List<String> beanDefinitionNames = new ArrayList<>(256);

	/** List of names of manually registered singletons, in registration order. */
	// <p>手动注册单例的名称列表，按注册顺序</p>
	private volatile Set<String> manualSingletonNames = new LinkedHashSet<>(16);

	/** Cached array of bean definition names in case of frozen configuration. */
	//<p>缓存的BeanDefinition名称数组，以防配置被冻结</p>
	@Nullable
	private volatile String[] frozenBeanDefinitionNames;

	/** Whether bean definition metadata may be cached for all beans. */
	//<p>冻结配置的标记</p>
	//Whether bean definition metadata may be cached for all beans.
	//是否可以为所有bean缓存bean定义元数据
	private volatile boolean configurationFrozen;


	/**
	 * Create a new DefaultListableBeanFactory.
	 * 创建一个新的 DefaultListableBeanFactory
	 */
	public DefaultListableBeanFactory() {
		super();
	}

	/**
	 * Create a new DefaultListableBeanFactory with the given parent.
	 * 使用给定的父级Bean工厂创建一个新的DefaultListableBeanFactory
	 * @param parentBeanFactory the parent BeanFactory
	 */
	public DefaultListableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		super(parentBeanFactory);
	}


	/**
	 * Specify an id for serialization purposes, allowing this BeanFactory to be
	 * deserialized from this id back into the BeanFactory object, if needed.
	 * 更新并保存序列化ID与当前工厂实例的映射关系
	 * 指定一个ID以进行序列化，如果需要的话，允许该BeanFactory从该ID反序列化为BeanFactory对象
	 */
	public void setSerializationId(@Nullable String serializationId) {
		//如果传入的序列化ID不为null
		if (serializationId != null) {
			//使用弱类型引用包装当前Bean工厂，再将映射关系添加到serializableFactories中
			serializableFactories.put(serializationId, new WeakReference<>(this));
		}
		//如果当前bean工厂的序列化ID不为null
		else if (this.serializationId != null) {
			//从serializableFactories中移除该serializationId对应的映射关系
			serializableFactories.remove(this.serializationId);
		}
		//对当前bean工厂的序列化ID重新赋值为传入的序列化ID
		this.serializationId = serializationId;
	}

	/**
	 * Return an id for serialization purposes, if specified, allowing this BeanFactory
	 * to be deserialized from this id back into the BeanFactory object, if needed.
	 * @since 4.1.2
	 */
	@Nullable
	public String getSerializationId() {
		return this.serializationId;
	}

	/**
	 * Set whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * If not, an exception will be thrown. This also applies to overriding aliases.
	 * <p>Default is "true".
	 *  通过注册一个具有相同名称的不同定义(自动替换前一个定义)来设置是否允许它覆盖bean
	 * 	定义。如果没有，将引发异常。这也适用于覆盖别名。
	 * @see #registerBeanDefinition
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Return whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * @since 4.1.2
	 */
	public boolean isAllowBeanDefinitionOverriding() {
		return this.allowBeanDefinitionOverriding;
	}

	/**
	 * Set whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>Default is "true". Turn this flag off to suppress class loading
	 * for lazy-init beans unless such a bean is explicitly requested.
	 * In particular, by-type lookups will then simply ignore bean definitions
	 * without resolved class name, instead of loading the bean classes on
	 * demand just to perform a type check.
	 * <p>返回是否允许急切加载Bean类，即使对于标记为'lazy-init'的bean定义也是如此</p>
	 * @see AbstractBeanDefinition#setLazyInit
	 */
	public void setAllowEagerClassLoading(boolean allowEagerClassLoading) {
		this.allowEagerClassLoading = allowEagerClassLoading;
	}

	/**
	 * Return whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>返回是否允许急切加载Bean类，即使对于标记为'lazy-init'的bean定义
	 * 	也是如此</p>
	 * @since 4.1.2
	 */
	public boolean isAllowEagerClassLoading() {
		return this.allowEagerClassLoading;
	}

	/**
	 * Set a {@link java.util.Comparator} for dependency Lists and arrays.
	 * @since 4.0
	 * @see org.springframework.core.OrderComparator
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 */
	public void setDependencyComparator(@Nullable Comparator<Object> dependencyComparator) {
		this.dependencyComparator = dependencyComparator;
	}

	/**
	 * Return the dependency comparator for this BeanFactory (may be {@code null}.
	 *  <p>返回此BeanFactory的依赖关系比较器(可以为null)</p>
	 * @since 4.0
	 */
	@Nullable
	public Comparator<Object> getDependencyComparator() {
		return this.dependencyComparator;
	}

	/**
	 * Set a custom autowire candidate resolver for this BeanFactory to use
	 * when deciding whether a bean definition should be considered as a
	 * candidate for autowiring.
	 */
	public void setAutowireCandidateResolver(AutowireCandidateResolver autowireCandidateResolver) {
		Assert.notNull(autowireCandidateResolver, "AutowireCandidateResolver must not be null");
		if (autowireCandidateResolver instanceof BeanFactoryAware beanFactoryAware) {
			beanFactoryAware.setBeanFactory(this);
		}
		this.autowireCandidateResolver = autowireCandidateResolver;
	}

	/**
	 * Return the autowire candidate resolver for this BeanFactory (never {@code null}).
	 */
	//<p>返回此BeanFactory的自动装配候选解析器(永远不为 null ),默认使用SimpleAutowireCandidateResolver</p>
	public AutowireCandidateResolver getAutowireCandidateResolver() {
		return this.autowireCandidateResolver;
	}


	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof DefaultListableBeanFactory otherListableFactory) {
			this.allowBeanDefinitionOverriding = otherListableFactory.allowBeanDefinitionOverriding;
			this.allowEagerClassLoading = otherListableFactory.allowEagerClassLoading;
			this.dependencyComparator = otherListableFactory.dependencyComparator;
			// A clone of the AutowireCandidateResolver since it is potentially BeanFactoryAware
			setAutowireCandidateResolver(otherListableFactory.getAutowireCandidateResolver().cloneIfNecessary());
			// Make resolvable dependencies (e.g. ResourceLoader) available here as well
			this.resolvableDependencies.putAll(otherListableFactory.resolvableDependencies);
		}
	}


	//---------------------------------------------------------------------
	// Implementation of remaining BeanFactory methods
	//---------------------------------------------------------------------

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		return getBean(requiredType, (Object[]) null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getBean(Class<T> requiredType, @Nullable Object... args) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		Object resolved = resolveBean(ResolvableType.forRawClass(requiredType), args, false);
		if (resolved == null) {
			throw new NoSuchBeanDefinitionException(requiredType);
		}
		return (T) resolved;
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType), true);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		return getBeanProvider(requiredType, true);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	/**
	 * 本地工厂中BeanDefinition对象映射【beanDefinitionMap】中是否存在beanName该键名
	 * @param beanName the name of the bean to look for -- 要寻找的bean名
	 */
	@Override
	public boolean containsBeanDefinition(String beanName) {
		//如果beanName为null，抛出异常
		Assert.notNull(beanName, "Bean name must not be null");
		//从Beand定义对象映射中判断beanName是否存在该键
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		String[] frozenNames = this.frozenBeanDefinitionNames;
		if (frozenNames != null) {
			return frozenNames.clone();
		}
		else {
			return StringUtils.toStringArray(this.beanDefinitionNames);
		}
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType), allowEagerInit);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		return new BeanObjectProvider<>() {
			@Override
			public T getObject() throws BeansException {
				T resolved = resolveBean(requiredType, null, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			public T getObject(Object... args) throws BeansException {
				T resolved = resolveBean(requiredType, args, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}
			@Override
			@Nullable
			public T getIfAvailable() throws BeansException {
				try {
					return resolveBean(requiredType, null, false);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifAvailable(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfAvailable();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@Override
			@Nullable
			public T getIfUnique() throws BeansException {
				try {
					return resolveBean(requiredType, null, true);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}
			@Override
			public void ifUnique(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfUnique();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					}
					catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}
			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> stream() {
				return Arrays.stream(getBeanNamesForTypedStream(requiredType, allowEagerInit))
						.map(name -> (T) getBean(name))
						.filter(bean -> !(bean instanceof NullBean));
			}
			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> orderedStream() {
				String[] beanNames = getBeanNamesForTypedStream(requiredType, allowEagerInit);
				if (beanNames.length == 0) {
					return Stream.empty();
				}
				Map<String, T> matchingBeans = CollectionUtils.newLinkedHashMap(beanNames.length);
				for (String beanName : beanNames) {
					Object beanInstance = getBean(beanName);
					if (!(beanInstance instanceof NullBean)) {
						matchingBeans.put(beanName, (T) beanInstance);
					}
				}
				Stream<T> stream = matchingBeans.values().stream();
				return stream.sorted(adaptOrderComparator(matchingBeans));
			}
		};
	}

	@Nullable
	private <T> T resolveBean(ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) {
		NamedBeanHolder<T> namedBean = resolveNamedBean(requiredType, args, nonUniqueAsNull);
		if (namedBean != null) {
			return namedBean.getBeanInstance();
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory dlfb) {
			return dlfb.resolveBean(requiredType, args, nonUniqueAsNull);
		}
		else if (parent != null) {
			ObjectProvider<T> parentProvider = parent.getBeanProvider(requiredType);
			if (args != null) {
				return parentProvider.getObject(args);
			}
			else {
				return (nonUniqueAsNull ? parentProvider.getIfUnique() : parentProvider.getIfAvailable());
			}
		}
		return null;
	}

	private String[] getBeanNamesForTypedStream(ResolvableType requiredType, boolean allowEagerInit) {
		return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(this, requiredType, true, allowEagerInit);
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,也包括Prototype级别的Bean对象，
	 * 并允许初始化lazy-init单例和由FactoryBeans创建的对象
	 * @param type the genericallmatchy typed class or interface to -- 通用化allmatchy类型化的类或接口
	 * @return 匹配给定对象类型（包含子类）的bean（或由FactoryBean创建的对象）的名称；
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,也包括Prototype级别的Bean对象，并允许初始化lazy-init单例
		//和由FactoryBeans创建的对象
		return getBeanNamesForType(type, true, true);
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称:
	 * <ol>
	 *  <li>从type中解析出Class对象【变量 resolved 】</li>
	 *  <li>如果resolved不为null 且 没有包含泛型参数,调用 {@link #getBeanNamesForType(Class, boolean, boolean)} 来
	 *  获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去</li>
	 *  <li>否则,调用 {@link #doGetBeanNamesForType(ResolvableType, boolean, boolean)} 来
	 *  获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去</li>
	 * </ol>
	 * @param type the generically typed class or interface to match -- 要匹配的通用类型的类或接口
	 * @param includeNonSingletons whether to include prototype or scoped beans too
	 * or just singletons (also applies to FactoryBeans)
	 * -- 是否也包括原型或作用域bean或仅包含单例（也适用于FactoryBeans)
	 * @param allowEagerInit whether to initialize <i>lazy-init singletons</i> and
	 * <i>objects created by FactoryBeans</i> (or by factory methods with a
	 * "factory-bean" reference) for the type check. Note that FactoryBeans need to be
	 * eagerly initialized to determine their type: So be aware that passing in "true"
	 * for this flag will initialize FactoryBeans and "factory-bean" references.
	 * -- 是否初始化 lazy-init单例和由FactoryBeans创建的对象(或通过带有'factory-bean'引用的
	 * 工厂创建方法）创建类型检查。注意，需要饿汉式初始化FactoryBeans以确定他们的类型：因此
	 * 请注意，为此标记传递'true'将初始化FactoryBean和'factory-bean'引用
	 * @return 匹配给定对象类型（包含子类）的bean（或由FactoryBean创建的对象）的名称；如果没有，则 返回一个空数组。
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		//从type中解析出Class对象
		Class<?> resolved = type.resolve();
		//如果resolved不为null 且 没有包含泛型参数
		if (resolved != null && !type.hasGenerics()) {
			//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去
			return getBeanNamesForType(resolved, includeNonSingletons, allowEagerInit);
		}
		else {
			//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去
			return doGetBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		}
		// getBeanNamesForType方法虽然也会调用doGetBeanNamesForType去解析有泛型的情况，但是getBeanNamesForType
		// 优先读取缓存，在能确定其匹配类型的情况下(即没有泛型)的情况下，getBeanNamesForType方法的性能优于
		// doGetBeanNamesForType方法。
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称:
	 * <ol>
	 *   <li>实际调用getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit)并
	 * 	 返回其结果</li>
	 * 	 <li>includeNonSingletons设置为true，包含非单例</li>
	 * 	 <li>allowEagerInit设置为true允许初始化FactoryBean和'factory-bean'引用</li>
	 * </ol>
	 * @param type the class or interface to match, or {@code null} for all bean names
	 *             -- 要匹配的类或接口，或者使用{@code null}为所有的bean名称
	 * @return
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		return getBeanNamesForType(type, true, true);
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
	 * <ol>
	 *   <li>如果该工厂的配置冻结了 或者 要匹配的类型或接口为null 或者 不允许初始化FactoryBean和'factory-bean'引用,
	 *    就调用 doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit)来
	 *    获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,然后返回出去
	 *   </li>
	 *   <li>定义一个Class-String[]的Map缓存，如果包含是否包含非单例，就引用单例和非单例Bean名称的映射Map【{@link #allBeanNamesByType}】；否则
	 *   引用仅依赖单例的bean名称的映射Map【{@link #singletonBeanNamesByType}】</li>
	 *   <li>从缓存中获取type对应的BeanName数组 【变量 resolvedBeanNames】,如果获取成功就返回出去</li>
	 *   <li>调用doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit)来
	 *   获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,然后返回出去
	 *   </li>
	 *   <li>获取工厂的类加载器，如果该类加载器加载过type就将type与属于type的Bean类的所有Bean名称的映射关系添加到cache中</li>
	 *   <li>返回属于type的Bean类的所有Bean名称</li>
	 * </ol>
	 * @param type 要匹配的通用类型的类或接口
	 * @param includeNonSingletons 是否包含非单例
	 * @param allowEagerInit 是否初始化FactoryBean和'factory-bean'引用
	 * @return 匹配给定对象类型（包含子类）的bean（或由FactoryBean创建的对象）的名称；如果没有，则 返回一个空数组。
	 * @see #doGetBeanNamesForType(ResolvableType, boolean, boolean)
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		//如果该工厂的配置冻结了 或者 要匹配的类型或接口为null 或者 不允许初始化FactoryBean和'factory-bean'引用
		if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
			//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,然后返回出去。
			return doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit);
		}
		//定义一个Class-String[]的Map缓存，如果包含是否包含非单例，就引用单例和非单例Bean名称的映射Map；
		//否则引用仅依赖单例的bean名称的映射Map
		Map<Class<?>, String[]> cache =
				(includeNonSingletons ? this.allBeanNamesByType : this.singletonBeanNamesByType);
		//从缓存中获取type对应的BeanName数组
		String[] resolvedBeanNames = cache.get(type);
		//如果获取成功，就返回出去
		if (resolvedBeanNames != null) {
			return resolvedBeanNames;
		}
		//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
		resolvedBeanNames = doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, true);
		//获取工厂的类加载器，如果该类加载器加载过type
		if (ClassUtils.isCacheSafe(type, getBeanClassLoader())) {
			//将type与属于type的Bean类的所有Bean名称的映射关系添加到cache中
			cache.put(type, resolvedBeanNames);
		}
		//返回属于type的Bean类的所有Bean名称
		return resolvedBeanNames;
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
	 * <ol>
	 *   <li>定义一个用于存储的匹配的bean名的ArrayList集合 【变量 result】</li>
	 *   <li><b>先从工厂收集到的所有BeanDefinition中查找：</b>
	 *    <ol>
	 *      <li>遍历工厂的BeanDefinition缓存集合【beanDefinitionNames】,元素为 beanName：
	 *       <ol>
	 *         <li>如果beanName不是别名:
	 *           <ol>
	 *             <li>获取beanName对应的合并后BootDefinition对象 【变量 mbd】</li>
	 *             <li>【这里的判断很多，主要就是判断mbd是否完整】如果mbd不是抽象类 且 (允许早期初始化 或者 mbd有指定bean类 或者  mbd没有设置延迟初始化
	 *             或者工厂配置了允许急切加载Bean类，即使对于标记为'lazy-init'的bean定义
	 *             也是如此【allowEagerClassLoading】) 且 mdb配置的FactoryBean名不需要急切初始化以确定其类型 ：
	 *				 <ol>
	 *				   <li>定义一个是否是FactoryBean类型标记【变量 isFactoryBean】：判断beanName和mbd所指的bean是否已定义为FactoryBean</li>
	 *				   <li>获取mbd的BeanDefinitionHolder对象 【变量 dbd】</li>
	 *				   <li>初始化匹配已找到标记为未找到【false,变量matchFound】</li>
	 *				   <li>定义允许FactoryBean初始化标记【变量 allowFactoryBeanInit】：只要允许饿汉式初始化 或者 beanName已经在单例对象的高速缓存Map集合
	 *				   【singletonObjects】有所属对象</li>
	 *				   <li>定义是非延时装饰标记【变量 isNonLazyDecorated】：dbd获取成功 且 mbd没有设置延时初始化</li>
	 *				   <li>如果不是FactoryBean类型，且(包含非单例 或者 beanName, mbd, dbd所指向的bean是单例),就将beanName的Bean类型是
	 *				   否与type匹配的结果赋值给matchFound</li>
	 *				   <li>如果是FactoryBean类型:
	 *				    <ol>
	 *				      <li>如果包含非单例 或者 mdb没有配置延时 或者 (允许FactoryBean初始化 且  beanName, mbd, dbd所指向的bean是单例),
	 *				      就将beanName的Bean类型是否与type匹配的结果赋值给matchFound</li>
	 *				      <li>如果不匹配， 将beanName改成解引用名，再来一次匹配：将beanName的Bean类型是否与type匹配的结果赋值给matchFound
	 *				      (此时的beanName是解引用名)</li>
	 *				    </ol>
	 *				   </li>
	 *				   <li>如果匹配成功,将beanName添加到result中</li>
	 *				 </ol>
	 *             </li>
	 *             <li>捕捉BeanFactory无法加载给定bean的指定类时引发的异常 和 当BeanFactory遇到无效的bean定义时引发的异常:
	 *              <ol>
	 *                <li>如果是允许马上初始化，重新抛出异常</li>
	 *                <li>构建日志消息对象，并打印跟踪日志：如果ex是CannotLoadBeanClassException，描述忽略Bean 'beanName'的Bean类加载失败；
	 *                否则描述忽略bean定义'beanName'中无法解析的元数据</li>
	 *                <li>将要注册的异常对象添加到 抑制异常列表【DefaultSingletonBeanRegistry#suppressedExceptions】中</li>
	 *              </ol>
	 *             </li>
	 *           </ol>
	 *         </li>
	 *       </ol>
	 *      </li>
	 *    </ol>
	 *   </li>
	 *   <li><b>从工厂收集到的手动注册的单例对象中查找：</b>
	 *    <ol>
	 *      <li>遍历 手动注册单例的名称列表【manualSingletonNames】：
	 *       <ol>
	 *         <li>如果beanName所指的对象属于FactoryBean实例：
	 *          <ol>
	 *           <li>如果(包含非单例 或者 beanName所指的对象是单例) 且 beanName对应的Bean类型与type匹配,就将beanName
	 *           添加到result中，然后 continue</li>
	 *           <li>否则，将beanName改成FactoryBean解引用名</li>
	 *          </ol>
	 *         </li>
	 *         <li>如果beanName对应的Bean类型与type匹配（此时的beanName有可能是FactoryBeany解引用名），就将beanName添加
	 *         到result中</li>
	 *         <li>捕捉没有此类bean定义异常,打印跟踪消息：无法检查名称为'beanName'的手动注册的单例</li>
	 *       </ol>
	 *      </li>
	 *    </ol>
	 *   </li>
	 *   <li>将result转换成Stirng数组返回出去</li>
	 * </ol>
	 * @param type 要匹配的通用类型的类或接口
	 * @param includeNonSingletons 是否包含非单例
	 * @param allowEagerInit 是否初始化FactoryBean和'factory-bean'引用
	 */
	private String[] doGetBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		//定义一个用于存储的匹配的bean名的ArrayList集合
		List<String> result = new ArrayList<>();

		// Check all bean definitions. 检查所有bean定义
		//遍历bean定义
		for (String beanName : this.beanDefinitionNames) {
			// Only consider bean as eligible if the bean name is not defined as alias for some other bean.
			//如果未将bean名称定义为其他bean的别名，则仅将bean视为可选。
			//如果beanName不是别名
			if (!isAlias(beanName)) {
				try {
					//获取beanName对应的合并后BootDefinition对象
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// Only check bean definition if it is complete.
					// 仅检查Bean定义是否完整
					// 如果mbd不是抽象
					//  	且 (允许饿汉式初始化
					//  		或者 mbd指定了bean类 或者 mbd没有设置延迟初始化
					//			或者 允许急切加载Bean类，即使对于标记为'lazy-init'的bean定义)
					//		且 mdb配置的FactoryBean名不需要急切初始化以确定其类型
					if (!mbd.isAbstract() && (allowEagerInit ||
							(mbd.hasBeanClass() || !mbd.isLazyInit() || isAllowEagerClassLoading()) &&
									!requiresEagerInitForType(mbd.getFactoryBeanName()))) {
						//是否是FactoryBean类型标记：判断beanName和mbd所指的bean是否已定义为FactoryBean
						boolean isFactoryBean = isFactoryBean(beanName, mbd);
						//获取mbd修饰的目标定义，
						// BeanDefinitionHolder:具有名称和别名的BeanDefinition的持有人
						BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
						//初始化匹配已找到标记为未找到
						boolean matchFound = false;
						//允许FactoryBean初始化标记：只要参数允许饿汉式初始化 或者 beanName已经在单例对象的高速缓存Map集合有所属对象
						boolean allowFactoryBeanInit = (allowEagerInit || containsSingleton(beanName));
						//是非延时装饰标记：mdb有配置BeanDefinitionHolder 且 mbd没有设置延时初始化
						boolean isNonLazyDecorated = (dbd != null && !mbd.isLazyInit());
						//如果不是FactoryBean类型
						if (!isFactoryBean) {
							//如果包含非单例 或者 beanName, mbd, dbd所指向的bean是单例
							if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
								//将beanName的Bean类型是否与type匹配的结果赋值给matchFound
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						//如果是FactoryBean类型
						else {
							//如果包含非单例 或者 mdb没有配置延时 或者 (允许FactoryBean初始化 且  beanName, mbd, dbd所指向的bean是单例)
							if (includeNonSingletons || isNonLazyDecorated ||
									(allowFactoryBeanInit && isSingleton(beanName, mbd, dbd))) {
								//将beanName的Bean类型是否与type匹配的结果赋值给matchFound
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
							//如果不匹配
							if (!matchFound) {
								// In case of FactoryBean, try to match FactoryBean instance itself next.
								// 如果是FactoryBean，请尝试接下来匹配FactoryBean实例本身
								// 将beanName改成解引用名
								beanName = FACTORY_BEAN_PREFIX + beanName;
								if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
									//将beanName的Bean类型是否与type匹配的结果赋值给matchFound(此时的beanName是解引用名)
									matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
								}
							}
						}
						//如果匹配成功
						if (matchFound) {
							//将beanName添加到result中
							result.add(beanName);
						}
					}
				}
				//捕捉BeanFactory无法加载给定bean的指定类时引发的异常 和 当BeanFactory遇到无效的bean定义时引发的异常
				catch (CannotLoadBeanClassException | BeanDefinitionStoreException ex) {
					//如果是允许马上初始化，重新抛出异常
					if (allowEagerInit) {
						throw ex;
					}
					// Probably a placeholder: let's ignore it for type matching purposes.
					// 可能是占位符：处于类型匹配的目的，我们将其忽略
					// 构建日志消息：如果ex是CannotLoadBeanClassException，描述忽略Bean 'beanName'的Bean类加载失败；否则
					// 描述忽略bean定义'beanName'中无法解析的元数据
					LogMessage message = (ex instanceof CannotLoadBeanClassException ?
							LogMessage.format("Ignoring bean class loading failure for bean '%s'", beanName) :
							LogMessage.format("Ignoring unresolvable metadata in bean definition '%s'", beanName));
					logger.trace(message, ex);
					// Register exception, in case the bean was accidentally unresolvable.
					//将要注册的异常对象添加到 抑制异常列表【DefaultSingletonBeanRegistry#suppressedExceptions】中
					onSuppressedException(ex);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Bean definition got removed while we were iterating -> ignore.
				}
			}
		}

		// Check manually registered singletons too.
		// 也检查手动注册的单例
		// 遍历 手动注册单例的名称列表
		for (String beanName : this.manualSingletonNames) {
			try {
				// In case of FactoryBean, match object created by FactoryBean.
				// 对于FactoryBean，请匹配FactoryBean创建的对象
				// 如果beanName所指的对象属于FactoryBean实例
				if (isFactoryBean(beanName)) {
					//如果(包含非单例 或者 beanName所指的对象是单例) 且 beanName对应的Bean类型与type匹配
					if ((includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type)) {
						//将beanName添加到result中
						result.add(beanName);
						// Match found for this bean: do not match FactoryBean itself anymore.
						// 为此bean找到匹配项：不再匹配FactoryBean本身
						continue;
					}
					// In case of FactoryBean, try to match FactoryBean itself next.
					// 如果是FactoryBean,请尝试接下来匹配FactoryBean本身
					beanName = FACTORY_BEAN_PREFIX + beanName;
				}
				// Match raw bean instance (might be raw FactoryBean).
				//匹配原始bean实例（可能是原始FactoryBean)
				//如果beanName对应的Bean类型与type匹配（此时的beanName有可能是FactoryBeany解引用名）
				if (isTypeMatch(beanName, type)) {
					//将beanName添加到result中
					result.add(beanName);
				}
			}
			//捕捉没有此类bean定义异常
			catch (NoSuchBeanDefinitionException ex) {
				// Shouldn't happen - probably a result of circular reference resolution...
				// 不应该发生-可能是循环引用决议结果
				//打印跟踪消息：无法检查名称为'beanName'的手动注册的单例
				logger.trace(LogMessage.format(
						"Failed to check manually registered singleton with name '%s'", beanName), ex);
			}
		}
		//将result转换成Stirng数组返回出去
		return StringUtils.toStringArray(result);
	}

	/**
	 * <p>根据给定参数尽可能的判断所指bean是否为单例，判断依据如下：
	 * 	<ol>
	 * 	    <li>如果存在dbd【不为 null 】:就判断mbd是否单例并返回其结果</li>
	 * 	    <li>否则判断beanName是否单例</li>
	 * 	</ol>
	 * </p>
	 * @param beanName bean名
	 * @param mbd  beanName对应的RootBeanDefinition，有可能是合并的RootBeanDefinition
	 * @param dbd  mbd修饰的目标定义
	 * @return
	 */
	private boolean isSingleton(String beanName, RootBeanDefinition mbd, @Nullable BeanDefinitionHolder dbd) {
		//如果存在mbd修饰的目标定义，就判断beanName对应的RootBeanDefinition是否单例，是就返回true，否则返回false；
		// 否则判断beanName是否单例，是就返回true；否则返回false
		return (dbd != null ? mbd.isSingleton() : isSingleton(beanName));
	}

	/**
	 * Check whether the specified bean would need to be eagerly initialized
	 * in order to determine its type.
	 * <p>检查是否需要急切初始化指定的bean以确定其类型,满足以下条件则认为需要急切初始化指定的bean以确定其类型<br/>
	 *		<ol>
	 *		 	<li>factoryBeanName确实是对应FactoryBean对象</li>
	 *		 	<li>该工厂的注册器没有该factoryBean对象。</li>
	 *		</ol>
	 * </p>
	 *
	 * @param factoryBeanName a factory-bean reference that the bean definition
	 * defines a factory method for
	 * -- 一个工厂bean引用，该BeanDefinition定义了一个工厂方法
	 * @return whether eager initialization is necessary
	 * 		-- 是否急切的初始化时必要的
	 */
	private boolean requiresEagerInitForType(@Nullable String factoryBeanName) {
		//fartoryBean引用名不为null 且 factoryBean引用名确实FactoryBean 且 该工厂的注册器注册器不包含factoryBean的单例实例 时
		// 返回true，否则返回false
		return (factoryBeanName != null && isFactoryBean(factoryBeanName) && !containsSingleton(factoryBeanName));
	}

	/**
	 * 返回与给定类型（包括子类）匹配的bean名称，根据bean定义或getObjectType的 值判断(如果是FactoryBenas)
	 * @param type the class or interface to match, or {@code null} for all concrete beans
	 *             --要匹配的类或接口，或者对于所有具体的bean,{@code null}
	 * @param <T> 要匹配的类型泛型
	 * @return 一个具有匹配bean的Map，其中包含bena名称作为键名，并包含对于bean实例作为值。
	 * @throws BeansException 如果无法创建一个bean
	 * @see #getBeansOfType(Class, boolean, boolean)
	 */
	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		return getBeansOfType(type, true, true);
	}

	/**
	 * 返回与给定类型（包括子类）匹配的bean名称，根据bean定义或getObjectType的 值判断(如果是FactoryBeans):
	 * <ol>
	 *  <li>获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的所有名称【变量 beanNames】</li>
	 *  <li>定义一个用于保存beanName和beanName所对应的实例对象的Map,长度为beanNames数组长度【变量result】</li>
	 *  <li>遍历类型匹配的beanNames,元素为beanName:
	 *   <ol>
	 *     <li>获取beanNamed的实例对象【变量 beanInstance】</li>
	 *     <li>如果实例对象不是NullBean,就将beanInstance与beanName绑定到result中</li>
	 *     <li>捕捉bean创建异常【{@link BeanCreationException}】【变量 ex】:
	 *      <ol>
	 *       <li>获取ex的具体异常【变量 rootCause】</li>
	 *       <li>如果具体异常 是 在引用当前正在创建的bean时引发异常【{@link BeanCurrentlyInCreationException}】:
	 *        <ol>
	 *         <li>将rootCause强转为BeanCreationException</li>
	 *         <li>获取引发改异常的bean名【变量 exBeanName】</li>
	 *         <li>如果exBeanName不为null 且 exBeanName正在创建:
	 *          <ol>
	 *           <li>如果日志级别是跟踪级别,打印跟踪日志：忽略与当前创建的bean的匹配 'exBeanName'</li>
	 *           <li>将要注册的异常对象添加到 抑制异常列表【{@link #suppressedExceptions}】 中</li>
	 *           <li>跳过本次循环</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>重新抛出异常ex</li>
	 *      </ol>
	 *     </li>
	 *   </ol>
	 *  </li>
	 *  <li>返回result</li>
	 * </ol>
	 * @param type the class or interface to match, or {@code null} for all concrete beans
	 *             --要匹配的类或接口，或者对于所有具体的bean,{@code null}
	 * @param <T> 要匹配的类型泛型
	 * @return 一个具有匹配bean的Map，其中包含bena名称作为键名，并包含对于bean实例作为值。
	 * @throws BeansException 如果无法创建一个bean
	 * @see #getBeansOfType(Class, boolean, boolean)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<String, T> getBeansOfType(
			@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit) throws BeansException {
		//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
		String[] beanNames = getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		//定义一个用于保存beanName和beanName所对应的实例对象的Map,长度为beanNames数组长度
		Map<String, T> result = CollectionUtils.newLinkedHashMap(beanNames.length);
		//遍历类型匹配的beanName
		for (String beanName : beanNames) {
			try {
				//获取beanNamed的实例对象
				Object beanInstance = getBean(beanName);
				//如果实例对象不是NullBean
				if (!(beanInstance instanceof NullBean)) {
					//将实例对象与beanName绑定到result中
					result.put(beanName, (T) beanInstance);
				}
			}
			//捕捉bean创建异常
			catch (BeanCreationException ex) {
				//获取具体异常
				Throwable rootCause = ex.getMostSpecificCause();
				//BeanCurrentlyInCreationException：在引用当前正在创建的bean时引发异常。通常在构造函数自动装配与
				// 当前构造的bean匹配时发生。
				//如果具体异常 是 在引用当前正在创建的bean时引发异常
				if (rootCause instanceof BeanCurrentlyInCreationException bce) {
					//获取引发改异常的bean名
					String exBeanName = bce.getBeanName();
					//获取引发改异常的bean名
					if (exBeanName != null && isCurrentlyInCreation(exBeanName)) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring match to currently created bean '" + exBeanName + "': " +
									ex.getMessage());
						}
						onSuppressedException(ex);
						// Ignore: indicates a circular reference when autowiring constructors.
						// We want to find matches other than the currently created bean itself.
						continue;
					}
				}
				throw ex;
			}
		}
		return result;
	}

	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		List<String> result = new ArrayList<>();
		for (String beanName : this.beanDefinitionNames) {
			BeanDefinition bd = this.beanDefinitionMap.get(beanName);
			if (bd != null && !bd.isAbstract() && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		for (String beanName : this.manualSingletonNames) {
			if (!result.contains(beanName) && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		return StringUtils.toStringArray(result);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
		String[] beanNames = getBeanNamesForAnnotation(annotationType);
		Map<String, Object> result = CollectionUtils.newLinkedHashMap(beanNames.length);
		for (String beanName : beanNames) {
			Object beanInstance = getBean(beanName);
			if (!(beanInstance instanceof NullBean)) {
				result.put(beanName, beanInstance);
			}
		}
		return result;
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		return findAnnotationOnBean(beanName, annotationType, true);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		return findMergedAnnotationOnBean(beanName, annotationType, allowFactoryBeanInit)
				.synthesize(MergedAnnotation::isPresent).orElse(null);
	}

	private <A extends Annotation> MergedAnnotation<A> findMergedAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit) {

		Class<?> beanType = getType(beanName, allowFactoryBeanInit);
		if (beanType != null) {
			MergedAnnotation<A> annotation =
					MergedAnnotations.from(beanType, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		if (containsBeanDefinition(beanName)) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// Check raw bean class, e.g. in case of a proxy.
			if (bd.hasBeanClass() && bd.getFactoryMethodName() == null) {
				Class<?> beanClass = bd.getBeanClass();
				if (beanClass != beanType) {
					MergedAnnotation<A> annotation =
							MergedAnnotations.from(beanClass, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
					if (annotation.isPresent()) {
						return annotation;
					}
				}
			}
			// Check annotations declared on factory method, if any.
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				MergedAnnotation<A> annotation =
						MergedAnnotations.from(factoryMethod, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
				if (annotation.isPresent()) {
					return annotation;
				}
			}
		}
		return MergedAnnotation.missing();
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableListableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void registerResolvableDependency(Class<?> dependencyType, @Nullable Object autowiredValue) {
		Assert.notNull(dependencyType, "Dependency type must not be null");
		if (autowiredValue != null) {
			if (!(autowiredValue instanceof ObjectFactory || dependencyType.isInstance(autowiredValue))) {
				throw new IllegalArgumentException("Value [" + autowiredValue +
						"] does not implement specified dependency type [" + dependencyType.getName() + "]");
			}
			this.resolvableDependencies.put(dependencyType, autowiredValue);
		}
	}

	@Override
	public boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {

		return isAutowireCandidate(beanName, descriptor, getAutowireCandidateResolver());
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * @param beanName the name of the bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(
			String beanName, DependencyDescriptor descriptor, AutowireCandidateResolver resolver)
			throws NoSuchBeanDefinitionException {

		String bdName = BeanFactoryUtils.transformedBeanName(beanName);
		if (containsBeanDefinition(bdName)) {
			return isAutowireCandidate(beanName, getMergedLocalBeanDefinition(bdName), descriptor, resolver);
		}
		else if (containsSingleton(beanName)) {
			return isAutowireCandidate(beanName, new RootBeanDefinition(getType(beanName)), descriptor, resolver);
		}

		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory dlfb) {
			// No bean definition found in this factory -> delegate to parent.
			return dlfb.isAutowireCandidate(beanName, descriptor, resolver);
		}
		else if (parent instanceof ConfigurableListableBeanFactory clfb) {
			// If no DefaultListableBeanFactory, can't pass the resolver along.
			return clfb.isAutowireCandidate(beanName, descriptor);
		}
		else {
			return true;
		}
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * @param beanName the name of the bean definition to check
	 * @param mbd the merged bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @param resolver the AutowireCandidateResolver to use for the actual resolution algorithm
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(String beanName, RootBeanDefinition mbd,
			DependencyDescriptor descriptor, AutowireCandidateResolver resolver) {

		String bdName = BeanFactoryUtils.transformedBeanName(beanName);
		resolveBeanClass(mbd, bdName);
		if (mbd.isFactoryMethodUnique && mbd.factoryMethodToIntrospect == null) {
			new ConstructorResolver(this).resolveFactoryMethodIfPossible(mbd);
		}
		BeanDefinitionHolder holder = (beanName.equals(bdName) ?
				this.mergedBeanDefinitionHolders.computeIfAbsent(beanName,
						key -> new BeanDefinitionHolder(mbd, beanName, getAliases(bdName))) :
				new BeanDefinitionHolder(mbd, beanName, getAliases(bdName)));
		return resolver.isAutowireCandidate(holder, descriptor);
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}
		return bd;
	}

	@Override
	public Iterator<String> getBeanNamesIterator() {
		CompositeIterator<String> iterator = new CompositeIterator<>();
		iterator.add(this.beanDefinitionNames.iterator());
		iterator.add(this.manualSingletonNames.iterator());
		return iterator;
	}

	@Override
	protected void clearMergedBeanDefinition(String beanName) {
		super.clearMergedBeanDefinition(beanName);
		this.mergedBeanDefinitionHolders.remove(beanName);
	}

	@Override
	public void clearMetadataCache() {
		super.clearMetadataCache();
		this.mergedBeanDefinitionHolders.clear();
		clearByTypeCache();
	}

	@Override
	public void freezeConfiguration() {
		this.configurationFrozen = true;
		this.frozenBeanDefinitionNames = StringUtils.toStringArray(this.beanDefinitionNames);
	}

	@Override
	public boolean isConfigurationFrozen() {
		return this.configurationFrozen;
	}

	/**
	 * Considers all beans as eligible for metadata caching
	 * if the factory's configuration has been marked as frozen.
	 * @see #freezeConfiguration()
	 */
	@Override
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return (this.configurationFrozen || super.isBeanEligibleForMetadataCaching(beanName));
	}

	@Override
	public void preInstantiateSingletons() throws BeansException {
		if (logger.isTraceEnabled()) {
			logger.trace("Pre-instantiating singletons in " + this);
		}
		// this.beanDefinitionNames 保存了所有的 beanNames
		// Iterate over a copy to allow for init methods which in turn register new bean definitions.
		// While this may not be part of the regular factory bootstrap, it does otherwise work fine.
		List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

		// Trigger initialization of all non-lazy singleton beans...
		// 下面这个循环，触发所有的非懒加载的 singleton beans 的初始化操作
		for (String beanName : beanNames) {
			// 合并父 Bean 中的配置，注意 <bean id="" class="" parent="" /> 中的 parent，用的不多吧，
			// 考虑到这可能会影响大家的理解，我在附录中解释了一下 "Bean 继承"，不了解的请到附录中看一下
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// 非抽象、非懒加载的 singletons。如果配置了 'abstract = true'，那是不需要初始化的
			if (!bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
				// 处理 FactoryBean
				if (isFactoryBean(beanName)) {
					// FactoryBean 的话，在 beanName 前面加上 ‘&’ 符号。再调用 getBean，getBean 方法别急
					Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
					// 判断当前 FactoryBean 是否是 SmartFactoryBean 的实现，此处忽略，直接跳过
					if (bean instanceof SmartFactoryBean<?> smartFactoryBean && smartFactoryBean.isEagerInit()) {
						getBean(beanName);
					}
				}
				else {
					// 对于普通的 Bean，只要调用 getBean(beanName) 这个方法就可以进行初始化了
					getBean(beanName);
				}
			}
		}

		// Trigger post-initialization callback for all applicable beans...
		// 到这里说明所有的非懒加载的 singleton beans 已经完成了初始化
		// 如果我们定义的 bean 是实现了 SmartInitializingSingleton 接口的，那么在这里得到回调，忽略
		for (String beanName : beanNames) {
			Object singletonInstance = getSingleton(beanName);
			if (singletonInstance instanceof SmartInitializingSingleton smartSingleton) {
				StartupStep smartInitialize = this.getApplicationStartup().start("spring.beans.smart-initialize")
						.tag("beanName", beanName);
				smartSingleton.afterSingletonsInstantiated();
				smartInitialize.end();
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanDefinitionRegistry interface
	//---------------------------------------------------------------------

	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {

		Assert.hasText(beanName, "Bean name must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");

		if (beanDefinition instanceof AbstractBeanDefinition abd) {
			try {
				abd.validate();
			}
			catch (BeanDefinitionValidationException ex) {
				throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
						"Validation of bean definition failed", ex);
			}
		}
		// 所有的 Bean 注册后会放入这个 beanDefinitionMap 中
		BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
		// 处理重复名称的 Bean 定义的情况
		if (existingDefinition != null) {
			if (!isAllowBeanDefinitionOverriding()) {
				// 如果不允许覆盖的话，抛异常
				throw new BeanDefinitionOverrideException(beanName, beanDefinition, existingDefinition);
			}
			else if (existingDefinition.getRole() < beanDefinition.getRole()) {
				// e.g. was ROLE_APPLICATION, now overriding with ROLE_SUPPORT or ROLE_INFRASTRUCTURE
				if (logger.isInfoEnabled()) {
					// log...用框架定义的 Bean 覆盖用户自定义的 Bean
					logger.info("Overriding user-defined bean definition for bean '" + beanName +
							"' with a framework-generated bean definition: replacing [" +
							existingDefinition + "] with [" + beanDefinition + "]");
				}
			}
			else if (!beanDefinition.equals(existingDefinition)) {
				if (logger.isDebugEnabled()) {
					// log...用新的 Bean 覆盖旧的 Bean
					logger.debug("Overriding bean definition for bean '" + beanName +
							"' with a different definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			else {
				if (logger.isTraceEnabled()) {
					// log...用同等的 Bean 覆盖旧的 Bean，这里指的是 equals 方法返回 true 的 Bean
					logger.trace("Overriding bean definition for bean '" + beanName +
							"' with an equivalent definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			// 覆盖
			this.beanDefinitionMap.put(beanName, beanDefinition);
		}
		else {
			if (isAlias(beanName)) {
				if (!isAllowBeanDefinitionOverriding()) {
					String aliasedName = canonicalName(beanName);
					if (containsBeanDefinition(aliasedName)) {  // alias for existing bean definition
						throw new BeanDefinitionOverrideException(
								beanName, beanDefinition, getBeanDefinition(aliasedName));
					}
					else {  // alias pointing to non-existing bean definition
						throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
								"Cannot register bean definition for bean '" + beanName +
								"' since there is already an alias for bean '" + aliasedName + "' bound.");
					}
				}
				else {
					removeAlias(beanName);
				}
			}
			// 判断是否已经有其他的 Bean 开始初始化了.
			// 注意，"注册Bean" 这个动作结束，Bean 依然还没有初始化，我们后面会有大篇幅说初始化过程，
			// 在 Spring 容器启动的最后，会 预初始化 所有的 singleton beans
			if (hasBeanCreationStarted()) {
				// Cannot modify startup-time collection elements anymore (for stable iteration)
				synchronized (this.beanDefinitionMap) {
					this.beanDefinitionMap.put(beanName, beanDefinition);
					List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames.size() + 1);
					updatedDefinitions.addAll(this.beanDefinitionNames);
					updatedDefinitions.add(beanName);
					this.beanDefinitionNames = updatedDefinitions;
					removeManualSingletonName(beanName);
				}
			}
			else {
				// 最正常的应该是进到这个分支。
				// 将 BeanDefinition 放到这个 map 中，这个 map 保存了所有的 BeanDefinition
				this.beanDefinitionMap.put(beanName, beanDefinition);
				// 这是个 ArrayList，所以会按照 bean 配置的顺序保存每一个注册的 Bean 的名字
				this.beanDefinitionNames.add(beanName);
				// 这是个 LinkedHashSet，代表的是手动注册的 singleton bean，
				// 注意这里是 remove 方法，到这里的 Bean 当然不是手动注册的
				// 手动指的是通过调用以下方法注册的 bean ：
				// registerSingleton(String beanName, Object singletonObject)
				// 这不是重点，解释只是为了不让大家疑惑。Spring 会在后面"手动"注册一些 Bean，
				// 如 "environment"、"systemProperties" 等 bean，我们自己也可以在运行时注册 Bean 到容器中的
				removeManualSingletonName(beanName);
			}
			// 这个不重要，在预初始化的时候会用到，不必管它。
			this.frozenBeanDefinitionNames = null;
		}

		if (existingDefinition != null || containsSingleton(beanName)) {
			resetBeanDefinition(beanName);
		}
		else if (isConfigurationFrozen()) {
			clearByTypeCache();
		}
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		Assert.hasText(beanName, "'beanName' must not be empty");

		BeanDefinition bd = this.beanDefinitionMap.remove(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}

		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames);
				updatedDefinitions.remove(beanName);
				this.beanDefinitionNames = updatedDefinitions;
			}
		}
		else {
			// Still in startup registration phase
			this.beanDefinitionNames.remove(beanName);
		}
		this.frozenBeanDefinitionNames = null;

		resetBeanDefinition(beanName);
	}

	/**
	 * Reset all bean definition caches for the given bean,
	 * including the caches of beans that are derived from it.
	 * <p>Called after an existing bean definition has been replaced or removed,
	 * triggering {@link #clearMergedBeanDefinition}, {@link #destroySingleton}
	 * and {@link MergedBeanDefinitionPostProcessor#resetBeanDefinition} on the
	 * given bean and on all bean definitions that have the given bean as parent.
	 * @param beanName the name of the bean to reset
	 * @see #registerBeanDefinition
	 * @see #removeBeanDefinition
	 */
	protected void resetBeanDefinition(String beanName) {
		// Remove the merged bean definition for the given bean, if already created.
		clearMergedBeanDefinition(beanName);

		// Remove corresponding bean from singleton cache, if any. Shouldn't usually
		// be necessary, rather just meant for overriding a context's default beans
		// (e.g. the default StaticMessageSource in a StaticApplicationContext).
		destroySingleton(beanName);

		// Notify all post-processors that the specified bean definition has been reset.
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.resetBeanDefinition(beanName);
		}

		// Reset all bean definitions that have the given bean as parent (recursively).
		for (String bdName : this.beanDefinitionNames) {
			if (!beanName.equals(bdName)) {
				BeanDefinition bd = this.beanDefinitionMap.get(bdName);
				// Ensure bd is non-null due to potential concurrent modification of beanDefinitionMap.
				if (bd != null && beanName.equals(bd.getParentName())) {
					resetBeanDefinition(bdName);
				}
			}
		}
	}

	/**
	 * Only allows alias overriding if bean definition overriding is allowed.
	 */
	@Override
	protected boolean allowAliasOverriding() {
		return isAllowBeanDefinitionOverriding();
	}

	/**
	 * Also checks for an alias overriding a bean definition of the same name.
	 */
	@Override
	protected void checkForAliasCircle(String name, String alias) {
		super.checkForAliasCircle(name, alias);
		if (!isAllowBeanDefinitionOverriding() && containsBeanDefinition(alias)) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Alias would override bean definition '" + alias + "'");
		}
	}

	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		super.registerSingleton(beanName, singletonObject);
		updateManualSingletonNames(set -> set.add(beanName), set -> !this.beanDefinitionMap.containsKey(beanName));
		clearByTypeCache();
	}

	@Override
	public void destroySingletons() {
		super.destroySingletons();
		updateManualSingletonNames(Set::clear, set -> !set.isEmpty());
		clearByTypeCache();
	}

	@Override
	public void destroySingleton(String beanName) {
		super.destroySingleton(beanName);
		removeManualSingletonName(beanName);
		clearByTypeCache();
	}

	private void removeManualSingletonName(String beanName) {
		updateManualSingletonNames(set -> set.remove(beanName), set -> set.contains(beanName));
	}

	/**
	 * Update the factory's internal set of manual singleton names.
	 * @param action the modification action
	 * @param condition a precondition for the modification action
	 * (if this condition does not apply, the action can be skipped)
	 */
	private void updateManualSingletonNames(Consumer<Set<String>> action, Predicate<Set<String>> condition) {
		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				if (condition.test(this.manualSingletonNames)) {
					Set<String> updatedSingletons = new LinkedHashSet<>(this.manualSingletonNames);
					action.accept(updatedSingletons);
					this.manualSingletonNames = updatedSingletons;
				}
			}
		}
		else {
			// Still in startup registration phase
			if (condition.test(this.manualSingletonNames)) {
				action.accept(this.manualSingletonNames);
			}
		}
	}

	/**
	 * Remove any assumptions about by-type mappings.
	 */
	private void clearByTypeCache() {
		this.allBeanNamesByType.clear();
		this.singletonBeanNamesByType.clear();
	}


	//---------------------------------------------------------------------
	// Dependency resolution functionality
	//---------------------------------------------------------------------

	@Override
	public <T> NamedBeanHolder<T> resolveNamedBean(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		NamedBeanHolder<T> namedBean = resolveNamedBean(ResolvableType.forRawClass(requiredType), null, false);
		if (namedBean != null) {
			return namedBean;
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof AutowireCapableBeanFactory acbf) {
			return acbf.resolveNamedBean(requiredType);
		}
		throw new NoSuchBeanDefinitionException(requiredType);
	}

	@SuppressWarnings("unchecked")
	@Nullable
	private <T> NamedBeanHolder<T> resolveNamedBean(
			ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) throws BeansException {

		Assert.notNull(requiredType, "Required type must not be null");
		String[] candidateNames = getBeanNamesForType(requiredType);

		if (candidateNames.length > 1) {
			List<String> autowireCandidates = new ArrayList<>(candidateNames.length);
			for (String beanName : candidateNames) {
				if (!containsBeanDefinition(beanName) || getBeanDefinition(beanName).isAutowireCandidate()) {
					autowireCandidates.add(beanName);
				}
			}
			if (!autowireCandidates.isEmpty()) {
				candidateNames = StringUtils.toStringArray(autowireCandidates);
			}
		}

		if (candidateNames.length == 1) {
			return resolveNamedBean(candidateNames[0], requiredType, args);
		}
		else if (candidateNames.length > 1) {
			Map<String, Object> candidates = CollectionUtils.newLinkedHashMap(candidateNames.length);
			for (String beanName : candidateNames) {
				if (containsSingleton(beanName) && args == null) {
					Object beanInstance = getBean(beanName);
					candidates.put(beanName, (beanInstance instanceof NullBean ? null : beanInstance));
				}
				else {
					candidates.put(beanName, getType(beanName));
				}
			}
			String candidateName = determinePrimaryCandidate(candidates, requiredType.toClass());
			if (candidateName == null) {
				candidateName = determineHighestPriorityCandidate(candidates, requiredType.toClass());
			}
			if (candidateName != null) {
				Object beanInstance = candidates.get(candidateName);
				if (beanInstance == null) {
					return null;
				}
				if (beanInstance instanceof Class) {
					return resolveNamedBean(candidateName, requiredType, args);
				}
				return new NamedBeanHolder<>(candidateName, (T) beanInstance);
			}
			if (!nonUniqueAsNull) {
				throw new NoUniqueBeanDefinitionException(requiredType, candidates.keySet());
			}
		}

		return null;
	}

	@Nullable
	private <T> NamedBeanHolder<T> resolveNamedBean(
			String beanName, ResolvableType requiredType, @Nullable Object[] args) throws BeansException {

		Object bean = getBean(beanName, null, args);
		if (bean instanceof NullBean) {
			return null;
		}
		return new NamedBeanHolder<>(beanName, adaptBeanInstance(beanName, bean, requiredType.toClass()));
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {

		descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());
		if (Optional.class == descriptor.getDependencyType()) {
			return createOptionalDependency(descriptor, requestingBeanName);
		}
		else if (ObjectFactory.class == descriptor.getDependencyType() ||
				ObjectProvider.class == descriptor.getDependencyType()) {
			return new DependencyObjectProvider(descriptor, requestingBeanName);
		}
		else if (javaxInjectProviderClass == descriptor.getDependencyType()) {
			return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
		}
		else {
			Object result = getAutowireCandidateResolver().getLazyResolutionProxyIfNecessary(
					descriptor, requestingBeanName);
			if (result == null) {
				result = doResolveDependency(descriptor, requestingBeanName, autowiredBeanNames, typeConverter);
			}
			return result;
		}
	}

	@Nullable
	public Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {

		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			Object shortcut = descriptor.resolveShortcut(this);
			if (shortcut != null) {
				return shortcut;
			}

			Class<?> type = descriptor.getDependencyType();
			Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
			if (value != null) {
				if (value instanceof String) {
					String strVal = resolveEmbeddedValue((String) value);
					BeanDefinition bd = (beanName != null && containsBean(beanName) ?
							getMergedBeanDefinition(beanName) : null);
					value = evaluateBeanDefinitionString(strVal, bd);
				}
				TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
				try {
					return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
				}
				catch (UnsupportedOperationException ex) {
					// A custom TypeConverter which does not support TypeDescriptor resolution...
					return (descriptor.getField() != null ?
							converter.convertIfNecessary(value, type, descriptor.getField()) :
							converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
				}
			}

			Object multipleBeans = resolveMultipleBeans(descriptor, beanName, autowiredBeanNames, typeConverter);
			if (multipleBeans != null) {
				return multipleBeans;
			}

			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			if (matchingBeans.isEmpty()) {
				if (isRequired(descriptor)) {
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				return null;
			}

			String autowiredBeanName;
			Object instanceCandidate;

			if (matchingBeans.size() > 1) {
				autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
				if (autowiredBeanName == null) {
					if (isRequired(descriptor) || !indicatesMultipleBeans(type)) {
						return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);
					}
					else {
						// In case of an optional Collection/Map, silently ignore a non-unique case:
						// possibly it was meant to be an empty collection of multiple regular beans
						// (before 4.3 in particular when we didn't even look for collection beans).
						return null;
					}
				}
				instanceCandidate = matchingBeans.get(autowiredBeanName);
			}
			else {
				// We have exactly one match.
				Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
				autowiredBeanName = entry.getKey();
				instanceCandidate = entry.getValue();
			}

			if (autowiredBeanNames != null) {
				autowiredBeanNames.add(autowiredBeanName);
			}
			if (instanceCandidate instanceof Class) {
				instanceCandidate = descriptor.resolveCandidate(autowiredBeanName, type, this);
			}
			Object result = instanceCandidate;
			if (result instanceof NullBean) {
				if (isRequired(descriptor)) {
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				result = null;
			}
			if (!ClassUtils.isAssignableValue(type, result)) {
				throw new BeanNotOfRequiredTypeException(autowiredBeanName, type, instanceCandidate.getClass());
			}
			return result;
		}
		finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Nullable
	private Object resolveMultipleBeans(DependencyDescriptor descriptor, @Nullable String beanName,
			@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {

		Class<?> type = descriptor.getDependencyType();

		if (descriptor instanceof StreamDependencyDescriptor streamDependencyDescriptor) {
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			Stream<Object> stream = matchingBeans.keySet().stream()
					.map(name -> descriptor.resolveCandidate(name, type, this))
					.filter(bean -> !(bean instanceof NullBean));
			if (streamDependencyDescriptor.isOrdered()) {
				stream = stream.sorted(adaptOrderComparator(matchingBeans));
			}
			return stream;
		}
		else if (type.isArray()) {
			Class<?> componentType = type.getComponentType();
			ResolvableType resolvableType = descriptor.getResolvableType();
			Class<?> resolvedArrayType = resolvableType.resolve(type);
			if (resolvedArrayType != type) {
				componentType = resolvableType.getComponentType().resolve();
			}
			if (componentType == null) {
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, componentType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			Object result = converter.convertIfNecessary(matchingBeans.values(), resolvedArrayType);
			if (result instanceof Object[] array) {
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				if (comparator != null) {
					Arrays.sort(array, comparator);
				}
			}
			return result;
		}
		else if (Collection.class.isAssignableFrom(type) && type.isInterface()) {
			Class<?> elementType = descriptor.getResolvableType().asCollection().resolveGeneric();
			if (elementType == null) {
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, elementType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			Object result = converter.convertIfNecessary(matchingBeans.values(), type);
			if (result instanceof List<?> list && list.size() > 1) {
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				if (comparator != null) {
					list.sort(comparator);
				}
			}
			return result;
		}
		else if (Map.class == type) {
			ResolvableType mapType = descriptor.getResolvableType().asMap();
			Class<?> keyType = mapType.resolveGeneric(0);
			if (String.class != keyType) {
				return null;
			}
			Class<?> valueType = mapType.resolveGeneric(1);
			if (valueType == null) {
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, valueType,
					new MultiElementDescriptor(descriptor));
			if (matchingBeans.isEmpty()) {
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			return matchingBeans;
		}
		else {
			return null;
		}
	}

	private boolean isRequired(DependencyDescriptor descriptor) {
		return getAutowireCandidateResolver().isRequired(descriptor);
	}

	private boolean indicatesMultipleBeans(Class<?> type) {
		return (type.isArray() || (type.isInterface() &&
				(Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))));
	}

	@Nullable
	private Comparator<Object> adaptDependencyComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator orderComparator) {
			return orderComparator.withSourceProvider(
					createFactoryAwareOrderSourceProvider(matchingBeans));
		}
		else {
			return comparator;
		}
	}

	private Comparator<Object> adaptOrderComparator(Map<String, ?> matchingBeans) {
		Comparator<Object> dependencyComparator = getDependencyComparator();
		OrderComparator comparator = (dependencyComparator instanceof OrderComparator orderComparator ?
				orderComparator : OrderComparator.INSTANCE);
		return comparator.withSourceProvider(createFactoryAwareOrderSourceProvider(matchingBeans));
	}

	private OrderComparator.OrderSourceProvider createFactoryAwareOrderSourceProvider(Map<String, ?> beans) {
		IdentityHashMap<Object, String> instancesToBeanNames = new IdentityHashMap<>();
		beans.forEach((beanName, instance) -> instancesToBeanNames.put(instance, beanName));
		return new FactoryAwareOrderSourceProvider(instancesToBeanNames);
	}

	/**
	 * Find bean instances that match the required type.
	 * Called during autowiring for the specified bean.
	 * @param beanName the name of the bean that is about to be wired
	 * @param requiredType the actual type of bean to look for
	 * (may be an array component type or collection element type)
	 * @param descriptor the descriptor of the dependency to resolve
	 * @return a Map of candidate names and candidate instances that match
	 * the required type (never {@code null})
	 * @throws BeansException in case of errors
	 * @see #autowireByType
	 * @see #autowireConstructor
	 */
	protected Map<String, Object> findAutowireCandidates(
			@Nullable String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {

		String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				this, requiredType, true, descriptor.isEager());
		Map<String, Object> result = CollectionUtils.newLinkedHashMap(candidateNames.length);
		for (Map.Entry<Class<?>, Object> classObjectEntry : this.resolvableDependencies.entrySet()) {
			Class<?> autowiringType = classObjectEntry.getKey();
			if (autowiringType.isAssignableFrom(requiredType)) {
				Object autowiringValue = classObjectEntry.getValue();
				autowiringValue = AutowireUtils.resolveAutowiringValue(autowiringValue, requiredType);
				if (requiredType.isInstance(autowiringValue)) {
					result.put(ObjectUtils.identityToString(autowiringValue), autowiringValue);
					break;
				}
			}
		}
		for (String candidate : candidateNames) {
			if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, descriptor)) {
				addCandidateEntry(result, candidate, descriptor, requiredType);
			}
		}
		if (result.isEmpty()) {
			boolean multiple = indicatesMultipleBeans(requiredType);
			// Consider fallback matches if the first pass failed to find anything...
			DependencyDescriptor fallbackDescriptor = descriptor.forFallbackMatch();
			for (String candidate : candidateNames) {
				if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, fallbackDescriptor) &&
						(!multiple || getAutowireCandidateResolver().hasQualifier(descriptor))) {
					addCandidateEntry(result, candidate, descriptor, requiredType);
				}
			}
			if (result.isEmpty() && !multiple) {
				// Consider self references as a final pass...
				// but in the case of a dependency collection, not the very same bean itself.
				for (String candidate : candidateNames) {
					if (isSelfReference(beanName, candidate) &&
							(!(descriptor instanceof MultiElementDescriptor) || !beanName.equals(candidate)) &&
							isAutowireCandidate(candidate, fallbackDescriptor)) {
						addCandidateEntry(result, candidate, descriptor, requiredType);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Add an entry to the candidate map: a bean instance if available or just the resolved
	 * type, preventing early bean initialization ahead of primary candidate selection.
	 */
	private void addCandidateEntry(Map<String, Object> candidates, String candidateName,
			DependencyDescriptor descriptor, Class<?> requiredType) {

		if (descriptor instanceof MultiElementDescriptor) {
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			if (!(beanInstance instanceof NullBean)) {
				candidates.put(candidateName, beanInstance);
			}
		}
		else if (containsSingleton(candidateName) || (descriptor instanceof StreamDependencyDescriptor streamDescriptor &&
				streamDescriptor.isOrdered())) {
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			candidates.put(candidateName, (beanInstance instanceof NullBean ? null : beanInstance));
		}
		else {
			candidates.put(candidateName, getType(candidateName));
		}
	}

	/**
	 * Determine the autowire candidate in the given set of beans.
	 * <p>Looks for {@code @Primary} and {@code @Priority} (in that order).
	 * @param candidates a Map of candidate names and candidate instances
	 * that match the required type, as returned by {@link #findAutowireCandidates}
	 * @param descriptor the target dependency to match against
	 * @return the name of the autowire candidate, or {@code null} if none found
	 */
	@Nullable
	protected String determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor descriptor) {
		Class<?> requiredType = descriptor.getDependencyType();
		String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
		if (primaryCandidate != null) {
			return primaryCandidate;
		}
		String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
		if (priorityCandidate != null) {
			return priorityCandidate;
		}
		// Fallback
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateName = entry.getKey();
			Object beanInstance = entry.getValue();
			if ((beanInstance != null && this.resolvableDependencies.containsValue(beanInstance)) ||
					matchesBeanName(candidateName, descriptor.getDependencyName())) {
				return candidateName;
			}
		}
		return null;
	}

	/**
	 * Determine the primary candidate in the given set of beans.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the primary candidate, or {@code null} if none found
	 * @see #isPrimary(String, Object)
	 */
	@Nullable
	protected String determinePrimaryCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String primaryBeanName = null;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (isPrimary(candidateBeanName, beanInstance)) {
				if (primaryBeanName != null) {
					boolean candidateLocal = containsBeanDefinition(candidateBeanName);
					boolean primaryLocal = containsBeanDefinition(primaryBeanName);
					if (candidateLocal && primaryLocal) {
						throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
								"more than one 'primary' bean found among candidates: " + candidates.keySet());
					}
					else if (candidateLocal) {
						primaryBeanName = candidateBeanName;
					}
				}
				else {
					primaryBeanName = candidateBeanName;
				}
			}
		}
		return primaryBeanName;
	}

	/**
	 * Determine the candidate with the highest priority in the given set of beans.
	 * <p>Based on {@code @jakarta.annotation.Priority}. As defined by the related
	 * {@link org.springframework.core.Ordered} interface, the lowest value has
	 * the highest priority.
	 * @param candidates a Map of candidate names and candidate instances
	 * (or candidate classes if not created yet) that match the required type
	 * @param requiredType the target dependency type to match against
	 * @return the name of the candidate with the highest priority,
	 * or {@code null} if none found
	 * @see #getPriority(Object)
	 */
	@Nullable
	protected String determineHighestPriorityCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		String highestPriorityBeanName = null;
		Integer highestPriority = null;
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (beanInstance != null) {
				Integer candidatePriority = getPriority(beanInstance);
				if (candidatePriority != null) {
					if (highestPriorityBeanName != null) {
						if (candidatePriority.equals(highestPriority)) {
							throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
									"Multiple beans found with the same priority ('" + highestPriority +
									"') among candidates: " + candidates.keySet());
						}
						else if (candidatePriority < highestPriority) {
							highestPriorityBeanName = candidateBeanName;
							highestPriority = candidatePriority;
						}
					}
					else {
						highestPriorityBeanName = candidateBeanName;
						highestPriority = candidatePriority;
					}
				}
			}
		}
		return highestPriorityBeanName;
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a primary bean.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance (can be null)
	 * @return whether the given bean qualifies as primary
	 */
	protected boolean isPrimary(String beanName, Object beanInstance) {
		String transformedBeanName = transformedBeanName(beanName);
		if (containsBeanDefinition(transformedBeanName)) {
			return getMergedLocalBeanDefinition(transformedBeanName).isPrimary();
		}
		return (getParentBeanFactory() instanceof DefaultListableBeanFactory parent &&
				parent.isPrimary(transformedBeanName, beanInstance));
	}

	/**
	 * Return the priority assigned for the given bean instance by
	 * the {@code jakarta.annotation.Priority} annotation.
	 * <p>The default implementation delegates to the specified
	 * {@link #setDependencyComparator dependency comparator}, checking its
	 * {@link OrderComparator#getPriority method} if it is an extension of
	 * Spring's common {@link OrderComparator} - typically, an
	 * {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}.
	 * If no such comparator is present, this implementation returns {@code null}.
	 * @param beanInstance the bean instance to check (can be {@code null})
	 * @return the priority assigned to that bean or {@code null} if none is set
	 */
	@Nullable
	protected Integer getPriority(Object beanInstance) {
		Comparator<Object> comparator = getDependencyComparator();
		if (comparator instanceof OrderComparator orderComparator) {
			return orderComparator.getPriority(beanInstance);
		}
		return null;
	}

	/**
	 * Determine whether the given candidate name matches the bean name or the aliases
	 * stored in this bean definition.
	 */
	protected boolean matchesBeanName(String beanName, @Nullable String candidateName) {
		return (candidateName != null &&
				(candidateName.equals(beanName) || ObjectUtils.containsElement(getAliases(beanName), candidateName)));
	}

	/**
	 * Determine whether the given beanName/candidateName pair indicates a self reference,
	 * i.e. whether the candidate points back to the original bean or to a factory method
	 * on the original bean.
	 */
	private boolean isSelfReference(@Nullable String beanName, @Nullable String candidateName) {
		return (beanName != null && candidateName != null &&
				(beanName.equals(candidateName) || (containsBeanDefinition(candidateName) &&
						beanName.equals(getMergedLocalBeanDefinition(candidateName).getFactoryBeanName()))));
	}

	/**
	 * Raise a NoSuchBeanDefinitionException or BeanNotOfRequiredTypeException
	 * for an unresolvable dependency.
	 */
	private void raiseNoMatchingBeanFound(
			Class<?> type, ResolvableType resolvableType, DependencyDescriptor descriptor) throws BeansException {

		checkBeanNotOfRequiredType(type, descriptor);

		throw new NoSuchBeanDefinitionException(resolvableType,
				"expected at least 1 bean which qualifies as autowire candidate. " +
				"Dependency annotations: " + ObjectUtils.nullSafeToString(descriptor.getAnnotations()));
	}

	/**
	 * Raise a BeanNotOfRequiredTypeException for an unresolvable dependency, if applicable,
	 * i.e. if the target type of the bean would match but an exposed proxy doesn't.
	 */
	private void checkBeanNotOfRequiredType(Class<?> type, DependencyDescriptor descriptor) {
		for (String beanName : this.beanDefinitionNames) {
			try {
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				Class<?> targetType = mbd.getTargetType();
				if (targetType != null && type.isAssignableFrom(targetType) &&
						isAutowireCandidate(beanName, mbd, descriptor, getAutowireCandidateResolver())) {
					// Probably a proxy interfering with target type match -> throw meaningful exception.
					Object beanInstance = getSingleton(beanName, false);
					Class<?> beanType = (beanInstance != null && beanInstance.getClass() != NullBean.class ?
							beanInstance.getClass() : predictBeanType(beanName, mbd));
					if (beanType != null && !type.isAssignableFrom(beanType)) {
						throw new BeanNotOfRequiredTypeException(beanName, type, beanType);
					}
				}
			}
			catch (NoSuchBeanDefinitionException ex) {
				// Bean definition got removed while we were iterating -> ignore.
			}
		}

		if (getParentBeanFactory() instanceof DefaultListableBeanFactory parent) {
			parent.checkBeanNotOfRequiredType(type, descriptor);
		}
	}

	/**
	 * Create an {@link Optional} wrapper for the specified dependency.
	 */
	private Optional<?> createOptionalDependency(
			DependencyDescriptor descriptor, @Nullable String beanName, final Object... args) {

		DependencyDescriptor descriptorToUse = new NestedDependencyDescriptor(descriptor) {
			@Override
			public boolean isRequired() {
				return false;
			}
			@Override
			public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
				return (!ObjectUtils.isEmpty(args) ? beanFactory.getBean(beanName, args) :
						super.resolveCandidate(beanName, requiredType, beanFactory));
			}
		};
		Object result = doResolveDependency(descriptorToUse, beanName, null, null);
		return (result instanceof Optional<?> optional ? optional : Optional.ofNullable(result));
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(ObjectUtils.identityToString(this));
		sb.append(": defining beans [");
		sb.append(StringUtils.collectionToCommaDelimitedString(this.beanDefinitionNames));
		sb.append("]; ");
		BeanFactory parent = getParentBeanFactory();
		if (parent == null) {
			sb.append("root of factory hierarchy");
		}
		else {
			sb.append("parent: ").append(ObjectUtils.identityToString(parent));
		}
		return sb.toString();
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	@Serial
	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("DefaultListableBeanFactory itself is not deserializable - " +
				"just a SerializedBeanFactoryReference is");
	}

	@Serial
	protected Object writeReplace() throws ObjectStreamException {
		if (this.serializationId != null) {
			return new SerializedBeanFactoryReference(this.serializationId);
		}
		else {
			throw new NotSerializableException("DefaultListableBeanFactory has no serialization id");
		}
	}


	/**
	 * Minimal id reference to the factory.
	 * Resolved to the actual factory instance on deserialization.
	 */
	private static class SerializedBeanFactoryReference implements Serializable {

		private final String id;

		public SerializedBeanFactoryReference(String id) {
			this.id = id;
		}

		private Object readResolve() {
			Reference<?> ref = serializableFactories.get(this.id);
			if (ref != null) {
				Object result = ref.get();
				if (result != null) {
					return result;
				}
			}
			// Lenient fallback: dummy factory in case of original factory not found...
			DefaultListableBeanFactory dummyFactory = new DefaultListableBeanFactory();
			dummyFactory.serializationId = this.id;
			return dummyFactory;
		}
	}


	/**
	 * A dependency descriptor marker for nested elements.
	 */
	private static class NestedDependencyDescriptor extends DependencyDescriptor {

		public NestedDependencyDescriptor(DependencyDescriptor original) {
			super(original);
			increaseNestingLevel();
		}
	}


	/**
	 * A dependency descriptor for a multi-element declaration with nested elements.
	 */
	private static class MultiElementDescriptor extends NestedDependencyDescriptor {

		public MultiElementDescriptor(DependencyDescriptor original) {
			super(original);
		}
	}


	/**
	 * A dependency descriptor marker for stream access to multiple elements.
	 */
	private static class StreamDependencyDescriptor extends DependencyDescriptor {

		private final boolean ordered;

		public StreamDependencyDescriptor(DependencyDescriptor original, boolean ordered) {
			super(original);
			this.ordered = ordered;
		}

		public boolean isOrdered() {
			return this.ordered;
		}
	}


	private interface BeanObjectProvider<T> extends ObjectProvider<T>, Serializable {
	}


	/**
	 * Serializable ObjectFactory/ObjectProvider for lazy resolution of a dependency.
	 */
	private class DependencyObjectProvider implements BeanObjectProvider<Object> {

		private final DependencyDescriptor descriptor;

		private final boolean optional;

		@Nullable
		private final String beanName;

		public DependencyObjectProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			this.descriptor = new NestedDependencyDescriptor(descriptor);
			this.optional = (this.descriptor.getDependencyType() == Optional.class);
			this.beanName = beanName;
		}

		@Override
		public Object getObject() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				Object result = doResolveDependency(this.descriptor, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		public Object getObject(final Object... args) throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName, args);
			}
			else {
				DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
					@Override
					public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
						return beanFactory.getBean(beanName, args);
					}
				};
				Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
				if (result == null) {
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				return result;
			}
		}

		@Override
		@Nullable
		public Object getIfAvailable() throws BeansException {
			try {
				if (this.optional) {
					return createOptionalDependency(this.descriptor, this.beanName);
				}
				else {
					DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
						@Override
						public boolean isRequired() {
							return false;
						}
					};
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifAvailable(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfAvailable();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Override
		@Nullable
		public Object getIfUnique() throws BeansException {
			DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
				@Override
				public boolean isRequired() {
					return false;
				}

				@Override
				@Nullable
				public Object resolveNotUnique(ResolvableType type, Map<String, Object> matchingBeans) {
					return null;
				}
			};
			try {
				if (this.optional) {
					return createOptionalDependency(descriptorToUse, this.beanName);
				}
				else {
					return doResolveDependency(descriptorToUse, this.beanName, null, null);
				}
			}
			catch (ScopeNotActiveException ex) {
				// Ignore resolved bean in non-active scope
				return null;
			}
		}

		@Override
		public void ifUnique(Consumer<Object> dependencyConsumer) throws BeansException {
			Object dependency = getIfUnique();
			if (dependency != null) {
				try {
					dependencyConsumer.accept(dependency);
				}
				catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope, even on scoped proxy invocation
				}
			}
		}

		@Nullable
		protected Object getValue() throws BeansException {
			if (this.optional) {
				return createOptionalDependency(this.descriptor, this.beanName);
			}
			else {
				return doResolveDependency(this.descriptor, this.beanName, null, null);
			}
		}

		@Override
		public Stream<Object> stream() {
			return resolveStream(false);
		}

		@Override
		public Stream<Object> orderedStream() {
			return resolveStream(true);
		}

		@SuppressWarnings("unchecked")
		private Stream<Object> resolveStream(boolean ordered) {
			DependencyDescriptor descriptorToUse = new StreamDependencyDescriptor(this.descriptor, ordered);
			Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
			return (result instanceof Stream ? (Stream<Object>) result : Stream.of(result));
		}
	}


	/**
	 * Separate inner class for avoiding a hard dependency on the {@code jakarta.inject} API.
	 * Actual {@code jakarta.inject.Provider} implementation is nested here in order to make it
	 * invisible for Graal's introspection of DefaultListableBeanFactory's nested classes.
	 */
	private class Jsr330Factory implements Serializable {

		public Object createDependencyProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			return new Jsr330Provider(descriptor, beanName);
		}

		private class Jsr330Provider extends DependencyObjectProvider implements Provider<Object> {

			public Jsr330Provider(DependencyDescriptor descriptor, @Nullable String beanName) {
				super(descriptor, beanName);
			}

			@Override
			@Nullable
			public Object get() throws BeansException {
				return getValue();
			}
		}
	}


	/**
	 * An {@link org.springframework.core.OrderComparator.OrderSourceProvider} implementation
	 * that is aware of the bean metadata of the instances to sort.
	 * <p>Lookup for the method factory of an instance to sort, if any, and let the
	 * comparator retrieve the {@link org.springframework.core.annotation.Order}
	 * value defined on it. This essentially allows for the following construct:
	 */
	private class FactoryAwareOrderSourceProvider implements OrderComparator.OrderSourceProvider {

		private final Map<Object, String> instancesToBeanNames;

		public FactoryAwareOrderSourceProvider(Map<Object, String> instancesToBeanNames) {
			this.instancesToBeanNames = instancesToBeanNames;
		}

		@Override
		@Nullable
		public Object getOrderSource(Object obj) {
			String beanName = this.instancesToBeanNames.get(obj);
			if (beanName == null || !containsBeanDefinition(beanName)) {
				return null;
			}
			RootBeanDefinition beanDefinition = getMergedLocalBeanDefinition(beanName);
			List<Object> sources = new ArrayList<>(2);
			Method factoryMethod = beanDefinition.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				sources.add(factoryMethod);
			}
			Class<?> targetType = beanDefinition.getTargetType();
			if (targetType != null && targetType != obj.getClass()) {
				sources.add(targetType);
			}
			return sources.toArray();
		}
	}

}
