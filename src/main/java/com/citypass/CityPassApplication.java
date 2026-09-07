package com.citypass;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.citypass.mapper")
@SpringBootApplication
@EnableScheduling
public class CityPassApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityPassApplication.class, args);
    }
}
