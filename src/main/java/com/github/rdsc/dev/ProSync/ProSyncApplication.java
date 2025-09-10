package com.github.rdsc.dev.ProSync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling // 開啟 Spring 排程功能
@EnableCaching
@SpringBootApplication
public class ProSyncApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProSyncApplication.class, args);
	}

}
