package cn.sakuraneko;

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
