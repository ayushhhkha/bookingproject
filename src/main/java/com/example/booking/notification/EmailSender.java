package com.example.booking.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    public void send(String to, String subject, String body) {
        log.info("EMAIL to={} subject=\"{}\" body=\"{}\"", to, subject, body);
    }
}