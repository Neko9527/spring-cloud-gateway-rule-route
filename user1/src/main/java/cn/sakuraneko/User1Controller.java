package cn.sakuraneko;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/getUser")
public class User1Controller {

    @Autowired
    private RemoteAuthService remoteAuthService;

    @GetMapping("/get")
    public String getById() {
        return "user1";
    }

    @GetMapping("/getAuth")
    public String getAuth() {
        return remoteAuthService.getAuth();
    }
}
