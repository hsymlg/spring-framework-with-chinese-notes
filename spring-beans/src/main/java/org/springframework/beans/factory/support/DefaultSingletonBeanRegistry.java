/*
 * Copyright 2002-2020 the original author or authors.
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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
/**
 * 共享bean实例的通用注册表，实现了SingletonBeanRegistry. 允许注册表中注册的单例应该被所有调用者共享，通过bean名称获得。
 *
 * 还支持登记的DisposableBean实例，（这可能会或不能正确的注册单例），关闭注册表时destroyed.
 * 可以注册bean之间的依赖关系，执行适当的关闭顺序。
 *
 * 这个类主要用作基类的BeanFactory实现， 提供基本的管理
 * singleton bean 实例功能。
 *
 * 一看名字，就知道这是一个SingletonBean注册的地方，此类也实现了SingletonBeanRegistry接口，继承了SimpleAliasRegistry。
 * 这也可以理解，DefaultSingletonBeanRegistry既有管理SingletonBean的功能，又提供了别名的功能，那当然可以继承SimpleAliasRegistry了。
 * DefaultSingletonBeanRegistry是一个通用的存储共享bean实例的地方，通过bean的名字获得bean。同时，它也给提供一次性bean的注册功能。
 * 这个类的主要作用是，给BeanFactory的实现，提供基本的管理singleton bean实例功能。
 * 这个类中，使用了三个主要的存储器（map）来分别存储singletonObject，singletonFactory，earlySingletonObject。
 * 当注册一个singleton object的时候，会在 singletonObject 的存储器中加入此 object，而在其他的两个存储器中移除。当然，这样的行为是可以在子类中去复写的。
 * 在 getSingleton的时候，spring的默认实现是，先从 singleton object 的存储器中去寻找，如果找不到，再从 early singleton object 存储器中寻找，
 * 再找不到，那就在寻找对应的 singleton factory，造出所需的 singleton object，然后返回，并将这个对象缓存到 earlySingletonObjects 里面 以便后续使用，并从singletonFactories 除去对应的BeanName。
 * 而 contains singleton 就是直接检查 singleton object 存储器了，其他的存储器不做检查。
 * 而 get singleton counts 也是统计 singleton object 的数量。
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/** Maximum number of suppressed exceptions to preserve. */
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;


	/** Cache of singleton objects: bean name to bean instance. */
	//是存放singleton对象的缓存，Bean Name --> Bean instance
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/** Cache of singleton factories: bean name to ObjectFactory. */
	// 是存放制造singleton的工厂对象的缓存， BeanName -->ObjectFactory
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/** Cache of early singleton objects: bean name to bean instance. */
	//是存放singletonFactory 制造出来的 singleton 的缓存早期单例对象缓存，也是保存 BeanName 和创建 bean instance 之间的关系，与
	//singletonObjects的不同之处在于，当一个单例 bean 被放到这里面后，那么当 bean 还 在创建过程中，就可以通过 getBean方法获取到了， 其目的是用来检测循环引用
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/** Set of registered singletons, containing the bean names in registration order. */
	//以上三个缓存是这个类存放单例bean的主要Map
	//就是单例注册表
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/** Names of beans that are currently in creation. */
	//目前正在创建中的单例bean的名称的集合
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** Names of beans currently excluded from in creation checks. */
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** Collection of suppressed Exceptions, available for associating related causes. */
	//存放异常出现的相关的原因的集合
	@Nullable
	private Set<Exception> suppressedExceptions;

	/** Flag that indicates whether we're currently within destroySingletons. */
	//标志，指示我们目前是否在销毁单例中
	private boolean singletonsCurrentlyInDestruction = false;

	/** Disposable bean instances: bean name to disposable instance. */
	//存放一次性bean的缓存
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/** Map between containing bean names: bean name to Set of bean names that the bean contains. */
	//外部bean与被包含在外部bean的所有内部bean集合包含关系的缓存
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/** Map between dependent bean names: bean name to Set of dependent bean names. */
	//指定bean与依赖指定bean的所有bean的依赖关系的缓存
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/** Map between depending bean names: bean name to Set of bean names for the bean's dependencies. */
	//指定bean与创建这个bean所需要依赖的所有bean的依赖关系的缓存
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	//SingletonBeanRegistry接口的registerSingleton方法的实现
	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		synchronized (this.singletonObjects) {
			Object oldObject = this.singletonObjects.get(beanName);
			//如果singletonObjects缓存找到有指定名称为beanName的对象，则表示该名称已被占用
			if (oldObject != null) {
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			//若该名称没被占用，真正的注册操作在这里实现
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>To be called for eager registration of singletons.
	 * @param beanName the name of the bean
	 * @param singletonObject the singleton object
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			// 因为singletonObjects类型是ConcurrentHashMap,并发Map不支持空值作为标志值，所以用NULL_OBJECT来代替
			this.singletonObjects.put(beanName, singletonObject);
			// beanName已被注册存放在singletonObjects缓存，那么singletonFactories不应该再持有名称为beanName的工厂
			this.singletonFactories.remove(beanName);
			// beanName已被注册存放在singletonObjects缓存，那么earlySingletonObjects不应该再持有名称为beanName的bean。
			this.earlySingletonObjects.remove(beanName);
			// beanName放进单例注册表中
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * 添加 名称为beanName的singletonFactory对象
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			// 判断singletonObjects内名字为beanName是否被占用，若没有，进行注册操作
			if (!this.singletonObjects.containsKey(beanName)) {
				this.singletonFactories.put(beanName, singletonFactory);
				this.earlySingletonObjects.remove(beanName);
				this.registeredSingletons.add(beanName);
			}
		}
	}

	// SingletonBeanRegistry接口的getSingleton方法的实现
	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		return getSingleton(beanName, true);
	}

	/**
	 * Return the (raw) singleton object registered under the given name.
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * @param beanName the name of the bean to look for
	 * @param allowEarlyReference whether early references should be created or not
	 * @return the registered singleton object, or {@code null} if none found
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		// Quick check for existing instance without full singleton lock
		Object singletonObject = this.singletonObjects.get(beanName);
		// 如果singletonObjects指定beanName的对象是不存在的
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			singletonObject = this.earlySingletonObjects.get(beanName);
			// 如果earlySingletonObjects指定的beanName的对象是不存在的且allowEarlyReference（提前依赖）是允许的
			if (singletonObject == null && allowEarlyReference) {
				synchronized (this.singletonObjects) {
					// Consistent creation of early reference within full singleton lock
					singletonObject = this.singletonObjects.get(beanName);
					// 如果存在指定beanName的singletonFactory对象
					if (singletonObject == null) {
						// singletonFactory创建指定的单例对象
						singletonObject = this.earlySingletonObjects.get(beanName);
						if (singletonObject == null) {
							//获取对应的ObjectFactory，调用其getOjbect() 方法进行Bean的创建
							ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
							if (singletonFactory != null) {
								singletonObject = singletonFactory.getObject();
								//将创建的Bean 实例 放入到 earlySingletonObjects 里面
								this.earlySingletonObjects.put(beanName, singletonObject);
								// 将beanName 从singletonFactories 里面移除
								this.singletonFactories.remove(beanName);
							}
						}
					}
				}
			}
		}
		return singletonObject;
	}

	/**
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * @param beanName the name of the bean
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 * with, if necessary
	 * @return the registered singleton object
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(beanName, "Bean name must not be null");
		synchronized (this.singletonObjects) {
			Object singletonObject = this.singletonObjects.get(beanName);
			// 如果singetonObjects缓存中不存在名称为beanName的对象
			if (singletonObject == null) {
				// 如果目前在销毁singellton
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				// 单例对象创建前的回调,注册到（singletonsCurrentlyInCreation）
				beforeSingletonCreation(beanName);
				boolean newSingleton = false;
				// 判断存储异常相关原因的集合是否已存在
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				// 若没有，刚创建异常集合的实例
				if (recordSuppressedExceptions) {
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					// 由参数给定的singletonFactory创建singleton对象,getObject方法的具体实现由ObjectFactory的子类决定
					singletonObject = singletonFactory.getObject();
					newSingleton = true;
				}
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						throw ex;
					}
				}
				catch (BeanCreationException ex) {
					if (recordSuppressedExceptions) {
						for (Exception suppressedException : this.suppressedExceptions) {
							ex.addRelatedCause(suppressedException);
						}
					}
					throw ex;
				}
				finally {
					// 结束前，将异常集合销毁掉
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
					// 单例创建之后的回调,默认的实现标志单例不要在创建了。
					afterSingletonCreation(beanName);
				}
				if (newSingleton) {
					// 注册创建后的单例
					addSingleton(beanName, singletonObject);
				}
			}
			return singletonObject;
		}
	}

	/**
	 * Register an exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * <p>The default implementation preserves any given exception in this registry's
	 * collection of suppressed exceptions, up to a limit of 100 exceptions, adding
	 * them as related causes to an eventual top-level {@link BeanCreationException}.
	 * 注册 发生在singeton bean 实例创建之间发生的异常
	 * @param ex the Exception to register
	 * @see BeanCreationException#getRelatedCauses()
	 */
	protected void onSuppressedException(Exception ex) {
		synchronized (this.singletonObjects) {
			if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * 移除名称为beanName的单例,主要在四个集合中移除
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.remove(beanName);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.remove(beanName);
		}
	}

	//singletonBeanRegistry接口的containsSingleton方法实现
	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	// singletonBeanRegistry接口的getSingletonNames方法实现
	@Override
	public String[] getSingletonNames() {
		// 对singletonObjects加锁，可能是为了防止registeredSingletons和singletonObjects出现不一致的问题
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	// singletonBeanRegistry接口的getSingletonCount方法实现
	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 * 返回 存放正在创建单例的集合是否包含指定名称为beanName的单例存在
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * 单例对象创建前的回调,默认实现singletonsCurrentlyInCreation集合注册正在创建的单例.
	 * 该方法主要的作用是在创建单例bean时,是否需要后置处理,如果需要就会回调该方法
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * 单例创建之后的回调,默认实现singletonCurrentlyInCreation集合移除正在创建的单例
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * 一次性bean注册，存放在disponsableBeans集合中
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * 注册两个bean之间的控制关系,例如内部bean和包含其的外部bean之间
	 * @param containedBeanName the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		synchronized (this.containedBeanMap) {
			// 从containedBeanMap缓存中查找外部bean名为containingBeanName的内部bean集合
			// 如果没有，刚新建一个存放内部bean的集合，并且存放在containedBeanMap缓存中
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			// 将名为containedBeanName的内部bean存放到内部bean集合
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		// 紧接着调用注册内部bean和外部bean的依赖关系的方法
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	// 这里是进行加入缓存，以便下次使用，里面的两个map为有向图的相反方向的相邻层级
	public void registerDependentBean(String beanName, String dependentBeanName) {
		String canonicalName = canonicalName(beanName);
		//先从容器中：bean名称-->全部依赖Bean名称集合找查找给定名称Bean的依赖Bean
		synchronized (this.dependentBeanMap) {
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			//向容器中：bean名称-->全部依赖Bean名称集合添加Bean的依赖信息
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}
		//从容器中：bean名称-->指定名称Bean的依赖Bean集合找查找给定名称
		synchronized (this.dependenciesForBeanMap) {
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			//向容器中：bean名称-->指定Bean的依赖Bean名称集合添加Bean的依赖信息
			//即，将Bean所依赖的Bean添加到容器的集合中
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		//先锁住
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	//其实是一个有向图的深度遍历算法，转化为有向图是否存在环的问题。
	//isDependent方法参数传入两个节点，如果有环返回true，否则返回false
	/*
	->符号记为一个有向的连接，注意Map<String, Set<String>> dependentBeanMap中的value是指向key的
	我们尝试连接A->B,那么以下两种情况可判断为有环：
		1.如果有B->A,
		2.存在某个X->A使B->X。
		 算法采用一个map来存某个节点的所有前驱节点，以下是伪代码描述

		// 尝试A->B,如果存在B->A返回true
		isDependent(String A, String B, Set<String> alreadySeen) {
		        if alreadySeen.Has(A) then // 略过所有已访问过的前驱
		            return false;
		        if map = null then
		            return false;
		        if map.get(A).has(B) then // 如果有B->A则有环
		            return true;
		       for X in map.get(A)// 寻找A的所有前驱,X->A
		            if isDependent(X,B) then // 如果有B->X则有环
		       return true;
		       alreadySeen.add(A)// 标记为已访问
		       return false;
		}
	 */
	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		// 如果alreadySeen 不为空，并且已经包含了 beanName 就返回false
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		// 将beanName 转为最终的name
		String canonicalName = canonicalName(beanName);
		// 从dependentBeanMap 获取对应的已经存在的依赖关系
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		// 如果为null,直接返回
		if (dependentBeans == null) {
			return false;
		}
		// 如果包含，返回true
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		//有其他的依赖关系，但是当前beanname不存在，就遍历，把当前beanname放到
		for (String transitiveDependency : dependentBeans) {
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			alreadySeen.add(beanName);
			// 迭代循环进行判断
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * 确定是否还存在名为beanName的被依赖关系
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * 返回依赖于指定的bean的所有bean的名称，如果有的话。
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 * 返回指定的bean依赖于所有的bean的名称，如果有的话。
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		// 如果没有的话返回new String[0]而不是null
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	// 销毁单例
	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		// 单例目前销毁标志开始
		synchronized (this.singletonObjects) {
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		// 销毁disponsableBeans缓存中所有单例bean
		synchronized (this.disposableBeans) {
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			destroySingleton(disposableBeanNames[i]);
		}
		// containedBeanMap缓存清空,dependentBeanMap缓存清空，dependenciesForBeanMap缓存清空
		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();
		// singeltonObjects缓存清空，singletonFactories缓存清空，earlySingletonObjects缓存清空，registeredSingletons缓存清空
		clearSingletonCache();
	}

	/**
	 * Clear all cached singleton instances in this registry.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			// 单例目前正在销毁标志为结束
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		destroyBean(beanName, disposableBean);
	}

	/**
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		// 这段代码告诉我们先移除要销毁依赖bean
		Set<String> dependencies;
		synchronized (this.dependentBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		if (dependencies != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			for (String dependentBeanName : dependencies) {
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now...
		// 销毁bean实例
		if (bean != null) {
			try {
				bean.destroy();
			}
			catch (Throwable ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// Trigger destruction of contained beans...
		Set<String> containedBeans;
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			// 从containedBeanMap缓存中移除要销毁的bean，递归移除它的包含内部bean集合
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		if (containedBeans != null) {
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		// 从其它bean的依赖bean集合中移除要销毁的bean
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		// 最后 从dependenciesForBeanMap缓存中移除要销毁的bean
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
