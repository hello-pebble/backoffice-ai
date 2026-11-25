package com.backoffice.dashboard

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import java.util.TimeZone

@SpringBootApplication
@EnableConfigurationProperties(OfficeProperties::class)
class DashboardApplication

fun main(args: Array<String>) {
    // 배포 환경(Railway)의 컨테이너 타임존은 보통 UTC다. OffsetDateTime.now()·LocalDate.now()와
    // JDBC 가 timestamptz 를 문자열로 읽을 때 전부 이 기본 타임존을 따르므로, 여기서 한 번만 고정해
    // 화면에 찍히는 모든 시각을 한국시간으로 맞춘다.
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    runApplication<DashboardApplication>(*args)
}
