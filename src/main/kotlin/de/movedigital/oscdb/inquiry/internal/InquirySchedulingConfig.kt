package de.movedigital.oscdb.inquiry.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("oscdb.inquiry.worker-enabled", havingValue = "true")
class InquirySchedulingConfig
