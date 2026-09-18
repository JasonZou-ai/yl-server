package cn.yl;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 银龄守护 · 老年人能力评估四端系统后端启动类。
 *
 * <p>模块化单体：api → application → domain ← infrastructure，业务域按 yl-modules 组织。
 */
@SpringBootApplication(scanBasePackages = "cn.yl")
@MapperScan("cn.yl.**.mapper")
public class YlApplication {

    public static void main(String[] args) {
        SpringApplication.run(YlApplication.class, args);
    }
}
