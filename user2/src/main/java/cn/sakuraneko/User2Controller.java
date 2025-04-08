package cn.sakuraneko;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/getUser")
public class User2Controller {

    @GetMapping("/get")
    public String getById() {
        return "user2";
    }
}
