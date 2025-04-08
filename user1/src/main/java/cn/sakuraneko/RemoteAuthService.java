package cn.sakuraneko;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "auth")
public interface RemoteAuthService {

    @GetMapping("/auth/token")
    String getAuth();
}
