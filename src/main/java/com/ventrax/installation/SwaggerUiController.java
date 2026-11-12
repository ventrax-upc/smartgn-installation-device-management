package com.ventrax.installation;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public class SwaggerUiController {

    @GetMapping(value = "/swagger-ui/index.html", produces = MediaType.TEXT_HTML_VALUE)
    Resource index() {
        return new ClassPathResource("smartgn-swagger/index.html");
    }

    @GetMapping(value = "/swagger-ui/smartgn.css", produces = "text/css")
    Resource theme() {
        return new ClassPathResource("smartgn-swagger/smartgn.css");
    }

    @GetMapping(value = "/swagger-ui/smartgn-init.js", produces = "application/javascript")
    Resource initializer() {
        return new ClassPathResource("smartgn-swagger/smartgn-init.js");
    }
}
