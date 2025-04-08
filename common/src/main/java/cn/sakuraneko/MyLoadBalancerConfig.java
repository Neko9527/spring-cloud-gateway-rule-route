package cn.sakuraneko;

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
