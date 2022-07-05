/*
 * Copyright 2002-2014 the original author or authors.
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

package org.springframework.beans.factory.annotation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.lang.Nullable;

/**
 * Extended {@link org.springframework.beans.factory.config.BeanDefinition}
 * interface that exposes {@link org.springframework.core.type.AnnotationMetadata}
 * about its bean class - without requiring the class to be loaded yet.
 * 现如今，大家都用spring注解扫描的方式进行开发，首先在业务类上添加一个注解，
 * 比如@Service、@Controller、@Component等，spring扫描所有类并判断是否加入相关注解，
 * 有注解的类会生成一个BeanDefinition并添加到IOC容器中。我们称注解BeanDefinition。
 * AnnotatedBeanDefinition是一个注解bd的接口，可以获取BeanDefinition注解相关数据。
 * 它有两个实现类ConfigurationClassBeanDefinition、ScannedGenericBeanDefinition和AnnotatedGenericBeanDefinition。
 * @author Juergen Hoeller
 * @since 2.5
 * @see AnnotatedGenericBeanDefinition
 * @see org.springframework.core.type.AnnotationMetadata
 */
public interface AnnotatedBeanDefinition extends BeanDefinition {

	/**
	 * Obtain the annotation metadata (as well as basic class metadata)
	 * for this bean definition's bean class.
	 * 获取注解信息
	 * @return the annotation metadata object (never {@code null})
	 */
	AnnotationMetadata getMetadata();

	/**
	 * Obtain metadata for this bean definition's factory method, if any.
	 * 获取此beanDefinition的工厂方法的元数据（如果有）。
	 * @return the factory method metadata, or {@code null} if none
	 * @since 4.1.1
	 */
	@Nullable
	MethodMetadata getFactoryMethodMetadata();

}
