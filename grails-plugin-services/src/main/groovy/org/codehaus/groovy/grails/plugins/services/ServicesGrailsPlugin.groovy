/*
 * Copyright 2004-2005 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.plugins.services

import grails.util.GrailsUtil
import groovy.transform.CompileStatic

import java.lang.reflect.Method
import java.lang.reflect.Modifier

import org.codehaus.groovy.grails.commons.GrailsServiceClass
import org.codehaus.groovy.grails.commons.ServiceArtefactHandler
import org.codehaus.groovy.grails.commons.spring.TypeSpecifyableTransactionProxyFactoryBean
import org.codehaus.groovy.grails.orm.support.GroovyAwareNamedTransactionAttributeSource
import org.springframework.beans.factory.config.MethodInvokingFactoryBean
import org.springframework.core.AliasRegistry
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.transaction.annotation.Transactional

/**
 * Configures services in the Spring context.
 *
 * @author Graeme Rocher
 * @since 0.4
 */
class ServicesGrailsPlugin {

    def version = GrailsUtil.getGrailsVersion()
    def loadAfter = ['hibernate', 'hibernate4']

    def watchedResources = ["file:./grails-app/services/**/*Service.groovy",
                            "file:./plugins/*/grails-app/services/**/*Service.groovy"]

    def doWithSpring = {
        xmlns tx:"http://www.springframework.org/schema/tx"
        tx.'annotation-driven'('transaction-manager':'transactionManager')

        for (GrailsServiceClass serviceClass in application.serviceClasses) {
            def providingPlugin = manager?.getPluginForClass(serviceClass.clazz)

            String beanName
            if (providingPlugin && !serviceClass.shortName.toLowerCase().startsWith(providingPlugin.name.toLowerCase())) {
                beanName = "${providingPlugin.name}${serviceClass.shortName}"
            } else {
                beanName = serviceClass.propertyName
            }
            def scope = serviceClass.getPropertyValue("scope")
            def lazyInit = serviceClass.hasProperty("lazyInit") ? serviceClass.getPropertyValue("lazyInit") : true

            "${serviceClass.fullName}ServiceClass"(MethodInvokingFactoryBean) { bean ->
                bean.lazyInit = lazyInit
                targetObject = ref("grailsApplication", true)
                targetMethod = "getArtefact"
                arguments = [ServiceArtefactHandler.TYPE, serviceClass.fullName]
            }

            def hasDataSource = (application.config?.dataSource || application.domainClasses)
            if (hasDataSource && shouldCreateTransactionalProxy(serviceClass)) {
                def props = new Properties()

                String attributes = 'PROPAGATION_REQUIRED'
                String datasourceName = serviceClass.datasource
                String suffix = datasourceName == GrailsServiceClass.DEFAULT_DATA_SOURCE ? '' : "_$datasourceName"
                if (application.config["dataSource$suffix"].readOnly) {
                    attributes += ',readOnly'
                }
                props."*" = attributes

                "${beanName}"(TypeSpecifyableTransactionProxyFactoryBean, serviceClass.clazz) { bean ->
                    if (scope) bean.scope = scope
                    bean.lazyInit = lazyInit
                    target = { innerBean ->
                        innerBean.lazyInit = lazyInit
                        innerBean.factoryBean = "${serviceClass.fullName}ServiceClass"
                        innerBean.factoryMethod = "newInstance"
                        innerBean.autowire = "byName"
                        if (scope) innerBean.scope = scope
                    }
                    proxyTargetClass = true
                    transactionAttributeSource = new GroovyAwareNamedTransactionAttributeSource(transactionalAttributes:props)
                    transactionManager = ref("transactionManager$suffix")
                }
            }
            else {
                "${beanName}"(serviceClass.getClazz()) { bean ->
                    bean.autowire =  true
                    bean.lazyInit = lazyInit
                    if (scope) {
                        bean.scope = scope
                    }
                }
            }
        }
    }
    
    def doWithApplicationContext = { ctx ->
        // should always be true...
        if(ctx instanceof AliasRegistry) {
            // all service property names, may contain duplicates if multiple service
            // artifacts exists withe same name
            def allServicePropertyNames = application.serviceClasses*.propertyName
            for (GrailsServiceClass serviceClass in application.serviceClasses) {
                def potentialAliasName = serviceClass.propertyName
                // if there is no bean with this alias name 
                //    AND
                // there is only 1 occurence of the name in allServicePropertyNames
                //   THEN
                // it is ok to create the alias
                if(!ctx.containsBean(potentialAliasName) && allServicePropertyNames.count(potentialAliasName) == 1) {
                    def providingPlugin = manager?.getPluginForClass(serviceClass.clazz)
                    // only create the alias if the service is provided by a plugin
                    if(providingPlugin) {
                        def beanName = "${providingPlugin.name}${serviceClass.shortName}"
                        ctx.registerAlias beanName, potentialAliasName
                    }
                }
            }
        }
        
    }

    @CompileStatic
    boolean shouldCreateTransactionalProxy(GrailsServiceClass serviceClass) {
        Class javaClass = serviceClass.clazz

        try {
            serviceClass.transactional &&
              !AnnotationUtils.findAnnotation(javaClass, grails.transaction.Transactional) &&
              !AnnotationUtils.findAnnotation(javaClass, Transactional) &&
                 !javaClass.methods.any { Method m -> AnnotationUtils.findAnnotation(m, Transactional) != null ||
                                                        AnnotationUtils.findAnnotation(m, grails.transaction.Transactional) != null}
        }
        catch (e) {
            return false
        }
    }

    def onChange = { event ->
        if (!event.source || !event.ctx) {
            return
        }

        if (event.source instanceof Class) {

            Class javaClass = event.source
            // do nothing for abstract classes
            if (Modifier.isAbstract(javaClass.modifiers)) return
            def serviceClass = application.addArtefact(ServiceArtefactHandler.TYPE, event.source)
            def serviceName = "${serviceClass.propertyName}"
            def scope = serviceClass.getPropertyValue("scope")

            String datasourceName = serviceClass.datasource
            String suffix = datasourceName == GrailsServiceClass.DEFAULT_DATA_SOURCE ? '' : "_$datasourceName"

            if (shouldCreateTransactionalProxy(serviceClass) && event.ctx.containsBean("transactionManager$suffix")) {

                def props = new Properties()
                String attributes = 'PROPAGATION_REQUIRED'
                if (application.config["dataSource$suffix"].readOnly) {
                    attributes += ',readOnly'
                }
                props."*" = attributes

                def beans = beans {
                    "${serviceClass.fullName}ServiceClass"(MethodInvokingFactoryBean) {
                        targetObject = ref("grailsApplication", true)
                        targetMethod = "getArtefact"
                        arguments = [ServiceArtefactHandler.TYPE, serviceClass.fullName]
                    }
                    "${serviceName}"(TypeSpecifyableTransactionProxyFactoryBean, serviceClass.clazz) { bean ->
                        if (scope) bean.scope = scope
                        target = { innerBean ->
                            innerBean.factoryBean = "${serviceClass.fullName}ServiceClass"
                            innerBean.factoryMethod = "newInstance"
                            innerBean.autowire = "byName"
                            if (scope) innerBean.scope = scope
                        }
                        proxyTargetClass = true
                        transactionAttributeSource = new GroovyAwareNamedTransactionAttributeSource(transactionalAttributes:props)
                        transactionManager = ref("transactionManager$suffix")
                    }
                }
                beans.registerBeans(event.ctx)
            }
            else {
                def beans = beans {
                    "$serviceName"(serviceClass.getClazz()) { bean ->
                        bean.autowire =  true
                        if (scope) {
                            bean.scope = scope
                        }
                    }
                }
                beans.registerBeans(event.ctx)
            }

        }
    }
}
