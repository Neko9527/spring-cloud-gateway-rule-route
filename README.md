# Spring-Cloud-Gateway实现自定义负载均衡

## 背景

​    平时工作项目中的有一个开发环境nacos ，有一个命名空间为dev,  该命名空间中有项目中所有服务（公共服务）。有的开发为了节约本地电脑资源，将自己部分有改动的服务A也注册到dev中，调试时将dev中的公共服务A下线，从而使调试时路由到本地的服务A。这种操作可能就会出现有另一个开发也这么操作，将自己的A服务注册到dev中，从而导致网关路由时服务混乱。

## 解决方法

1. 每一个开发自己建一个新的namespace，将自己的服务全部注册到属于自己的namespace中，这是当前项目中的使用的办法，这样虽然共用了nacos，缺点是并没有利用到开发环境中部署的公共服务，有的开发说还不如自己本地启动一个nacos。
2. 每个开发注册自己的服务到dev，并在启动服务时做一个标记，调试时在请求头上加上改标记，在路由时根据标记来路由到相同标记的服务上，如果没有相同标记的服务就默认使用dev的公共服务。

## 实现

​    实现之前想到`spring-cloud-starter-loadbalancer` 默认的负载均衡策略是轮询，先来看看它的大致实现原理。

```java
public class RoundRobinLoadBalancer implements ReactorServiceInstanceLoadBalancer {
    private static final Log log = LogFactory.getLog(RoundRobinLoadBalancer.class);
    final AtomicInteger position;
    final String serviceId;
    ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;

    public RoundRobinLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider, String serviceId) {
        this(serviceInstanceListSupplierProvider, serviceId, (new Random()).nextInt(1000));
    }

    public RoundRobinLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider, String serviceId, int seedPosition) {
        this.serviceId = serviceId;
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
        this.position = new AtomicInteger(seedPosition);
    }

    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = (ServiceInstanceListSupplier)this.serviceInstanceListSupplierProvider.getIfAvailable(NoopServiceInstanceListSupplier::new);
        return supplier.get(request).next().map((serviceInstances) -> {
            return this.processInstanceResponse(supplier, serviceInstances);
        });
    }

    private Response<ServiceInstance> processInstanceResponse(ServiceInstanceListSupplier supplier, List<ServiceInstance> serviceInstances) {
        Response<ServiceInstance> serviceInstanceResponse = this.getInstanceResponse(serviceInstances);
        if (supplier instanceof SelectedInstanceCallback && serviceInstanceResponse.hasServer()) {
            ((SelectedInstanceCallback)supplier).selectedServiceInstance((ServiceInstance)serviceInstanceResponse.getServer());
        }

        return serviceInstanceResponse;
    }

    private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            if (log.isWarnEnabled()) {
                log.warn("No servers available for service: " + this.serviceId);
            }

            return new EmptyResponse();
        } else if (instances.size() == 1) {
            return new DefaultResponse((ServiceInstance)instances.get(0));
        } else {
            int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;
            ServiceInstance instance = (ServiceInstance)instances.get(pos % instances.size());
            return new DefaultResponse(instance);
        }
    }
}
```

可以看到先是实现`ReactorServiceInstanceLoadBalancer`，主要逻辑就是在choose()方法中。轮询逻辑主要就是`getInstanceResponse`最后else中三行代码，看来也没有那么高深。

我们自己来建一个`MyLoadBalancer`也来实现`ReactorServiceInstanceLoadBalancer`

```java
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.*;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MyLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final String META_DATA_KEY = "version";
    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;

    public MyLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider) {
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        RequestDataContext context = (RequestDataContext) request.getContext();
        HttpHeaders headers = context.getClientRequest().getHeaders();
        ServiceInstanceListSupplier supplier = serviceInstanceListSupplierProvider.getIfAvailable();
        //根据请求头的参数来路由服务实例
        String version = headers.getFirst(META_DATA_KEY);
        return supplier.get().next().map(serviceInstances -> this.filterServiceInstance(serviceInstances, version));
    }

    public Response<ServiceInstance> filterServiceInstance(List<ServiceInstance> serviceInstances, String targetVersion) {
        if (serviceInstances.isEmpty()) {
            return new EmptyResponse();
        }
        List<ServiceInstance> publicServices = new ArrayList<>(serviceInstances.size());
        for (ServiceInstance serviceInstance : serviceInstances) {
            //在服务启动时添加一个元数据key-value  key为version
            String metaVersion = serviceInstance.getMetadata().get(META_DATA_KEY);
            if (!StringUtils.hasText(metaVersion)) {
                publicServices.add(serviceInstance);
            }
            //说明匹配到了我们需要的服务直接返回
            if (Objects.equals(targetVersion, metaVersion)) {
                return new DefaultResponse(serviceInstance);
            }
        }
        if (publicServices.isEmpty()) {
            return new EmptyResponse();
        }
        //没有匹配到对应版本的服务,直接返回公共服务
        return new DefaultResponse(publicServices.get(0));
    }
}
```

这里我们假设在调试时请求头上加上version来区分需要路由到哪个服务，在服务启动时将我们自己定义的version写入服务的metadata。

启动时加 `-Dspring.cloud.nacos.discovery.metadata.version="your value"`，当然version也可以时任意的可以的字符串。

最后还需要一个配置类使我们自定义的负载均衡生效

```java
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
//设置默认的LoadBalancerClient
@LoadBalancerClients(defaultConfiguration = MyLoadBalancer.class)
//只想在dev环境生效
//@Profile("dev")
public class MyLoadBalancerConfig {

    @Bean
    public ReactorLoadBalancer<ServiceInstance> myLoadBalancer(Environment environment,
                                                               LoadBalancerClientFactory loadBalancerClientFactory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        ObjectProvider<ServiceInstanceListSupplier> lazyProvider = loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class);
        return new MyLoadBalancer(lazyProvider);
    }

}

```

## 请求头传递

为了服务之间相互调用也能从请求头中拿到version，我们需要将请求头传递。

```java
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;

@Component
public class FeignRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate requestTemplate) {
        // 获取当前请求上下文
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            // 拿到所有 header 并设置到 Feign 请求中
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                String value = request.getHeader(name);
                requestTemplate.header(name, value);
            }
        }
    }
}
```