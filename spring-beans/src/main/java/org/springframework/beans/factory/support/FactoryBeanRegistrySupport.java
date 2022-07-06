/*
 * Copyright 2002-2021 the original author or authors.
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.lang.Nullable;

/**
 * Support base class for singleton registries which need to handle
 * {@link org.springframework.beans.factory.FactoryBean} instances,
 * integrated with {@link DefaultSingletonBeanRegistry}'s singleton management.
 *
 * <p>Serves as base class for {@link AbstractBeanFactory}.
 * 这是一个抽象类，抽象类继承了DefaultSingletonBeanRegistry类，增加了对FactoryBean的处理
 * @author Juergen Hoeller
 * @since 2.5.1
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/** Cache of singleton objects created by FactoryBeans: FactoryBean name to object. */
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);


	/**
	 * 通过给定的factoryBean来确定真实的FactoryBean类型
	 * AccessController类用于访问控制操作和决策,其中doPrivileged为本地方法
	 * Determine the type for the given FactoryBean.
	 * @param factoryBean the FactoryBean instance to check
	 * @return the FactoryBean's object type,
	 * or {@code null} if the type cannot be determined yet
	 */
	@Nullable
	protected Class<?> getTypeForFactoryBean(FactoryBean<?> factoryBean) {
		try {
			return factoryBean.getObjectType();
		}
		catch (Throwable ex) {
			// Thrown from the FactoryBean's getObjectType implementation.
			logger.info("FactoryBean threw exception from getObjectType, despite the contract saying " +
					"that it should return null if the type of its object cannot be determined yet", ex);
			return null;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean, if available
	 * in cached form. Quick check for minimal synchronization.
	 * 该方法很简单,从指定的FactoryBean的缓存中去拿,然后返回.注意:提一点factoryBean和普通bean的区别:
	 * 1.factoryBean也是一种bean,只不过是是一种工厂bean,是一个接口,一般实现该接口是用来创建比较复杂的对象实例,返回的不是指定类型的对象实例,而是通过自身方法getObject返回的对象,
	 * 2.普通的bean:就是我们通过xml来配置后交给容器帮我们去创建初始化直接通过getBean来获取即可.
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean,
	 * or {@code null} if not available
	 */
	@Nullable
	protected Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}

	/**
	 * Obtain an object to expose from the given FactoryBean.
	 * 从给定的factory中获取暴露的object
	 * @param factory the FactoryBean instance  FactoryBean的具体实例
	 * @param beanName the name of the bean  bean的name
	 * @param shouldPostProcess whether the bean is subject to post-processing 是否需要后置处理
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
		//先就是简单的判断,是否是单例和包含该单例bean
		if (factory.isSingleton() && containsSingleton(beanName)) {
			synchronized (getSingletonMutex()) {
				//先从缓存中去拿
				Object object = this.factoryBeanObjectCache.get(beanName);
				//这里表示没有拿到
				if (object == null) {
					//实质还是去调用getObject()去拿,这里分装了一层
					object = doGetObjectFromFactoryBean(factory, beanName);
					// Only post-process and store if not put there already during getObject() call above
					// (e.g. because of circular reference processing triggered by custom getBean calls)
					//处理了仅有doGetObjectFromFactoryBean调用getObject()期间保存时需要进行后置处理,如循环引用的问题
					Object alreadyThere = this.factoryBeanObjectCache.get(beanName);
					if (alreadyThere != null) {
						object = alreadyThere;
					}
					else {
						//后置处理
						if (shouldPostProcess) {
							//通过传入的beanName来判断当前bean是否正在创建中
							if (isSingletonCurrentlyInCreation(beanName)) {
								// Temporarily return non-post-processed object, not storing it yet..
								//这里返回的是临时的非后置处理的object,并不保存它
								return object;
							}
							//当前单例bean创建之前的回调该方法处理
							beforeSingletonCreation(beanName);
							try {
								//对object进行后置处理,如果在FactoryBean找不到该object抛如下异常
								object = postProcessObjectFromFactoryBean(object, beanName);
							}
							catch (Throwable ex) {
								throw new BeanCreationException(beanName,
										"Post-processing of FactoryBean's singleton object failed", ex);
							}
							finally {
								//当所有的条件具备完成,最后调用该方法对单例bean创建之后的一些处理,如:不能重复创建,
								afterSingletonCreation(beanName);
							}
						}
						if (containsSingleton(beanName)) {
							this.factoryBeanObjectCache.put(beanName, object);
						}
					}
				}
				//返回从FactoryBean拿到的bean
				return object;
			}
		}
		//这里说明是factory不是单例且beanName在缓存中找不到
		else {
			//1.从factoryBean中去拿
			Object object = doGetObjectFromFactoryBean(factory, beanName);
			//需要后置处理
			if (shouldPostProcess) {
				try {
					//从factoryBean拿到object后对其后置处理逻辑
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				catch (Throwable ex) {
					throw new BeanCreationException(beanName, "Post-processing of FactoryBean's object failed", ex);
				}
			}
			//最后返回该object
			return object;
		}
	}

	/**
	 * 从factory(该factory实质是FactoryBean的实例)获取暴露的object对象
	 * 在getObjectFromFactoryBean()方法中我们可以清楚的看到
	 * 当首次从缓存 factoryBeanObjectCache.get(beanName)中去拿的时候没拿到时,调用该方法
	 * 在该方法中我们可以看到 它是调用FactoryBean的getObject方法去找,如果找到了返回
	 * 没找到做了处理:
	 * 1.先调用isSingletonCurrentlyInCreation该返回判断当前bean是否在创建中
	 * 2.如果返回true,抛当前bean在创建中异常
	 * 3.如果返回false,创建一个空bean返回
	 * Obtain an object to expose from the given FactoryBean.
	 * @param factory the FactoryBean instance
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, String beanName) throws BeanCreationException {
		Object object;
		try {
			//这里是真正的获取object的方法入口
			object = factory.getObject();
		}
		catch (FactoryBeanNotInitializedException ex) {
			throw new BeanCurrentlyInCreationException(beanName, ex.toString());
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "FactoryBean threw exception on object creation", ex);
		}

		// Do not accept a null value for a FactoryBean that's not fully
		// initialized yet: Many FactoryBeans just return null then.
		//这里表明从FactoryBean没拿到,调用isSingletonCurrentlyInCreation判断该bean是否在创建中:
		//如果返回true,抛以下异常,当前bean在创建中
		if (object == null) {
			if (isSingletonCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(
						beanName, "FactoryBean which is currently in creation returned null from getObject");
			}
			//返回false话,创建一个空bean返回
			object = new NullBean();
		}
		return object;
	}

	/**
	 * Post-process the given object that has been obtained from the FactoryBean.
	 * The resulting object will get exposed for bean references.
	 * <p>The default implementation simply returns the given object as-is.
	 * Subclasses may override this, for example, to apply post-processors.
	 * @param object the object obtained from the FactoryBean.
	 * @param beanName the name of the bean
	 * @return the object to expose
	 * @throws org.springframework.beans.BeansException if any post-processing failed
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) throws BeansException {
		return object;
	}

	/**
	 * Get a FactoryBean for the given bean if possible.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance
	 * @return the bean instance as FactoryBean
	 * @throws BeansException if the given bean cannot be exposed as a FactoryBean
	 */
	protected FactoryBean<?> getFactoryBean(String beanName, Object beanInstance) throws BeansException {
		if (!(beanInstance instanceof FactoryBean)) {
			throw new BeanCreationException(beanName,
					"Bean instance of type [" + beanInstance.getClass() + "] is not a FactoryBean");
		}
		return (FactoryBean<?>) beanInstance;
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanObjectCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanObjectCache.clear();
		}
	}

}
