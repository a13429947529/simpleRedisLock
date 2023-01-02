package com.yxlm.api;

import com.yxlm.annotation.YxlmLock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestApi {

    @GetMapping("/testLock")
    @YxlmLock(lockKey = "yxlmLock")
    public Object test(){
        try {
            Thread.sleep(30000);
        }catch (Exception e){

        }
        return "123";
    }
}
