package com.backoffice.dashboard

import java.time.LocalDate

/**
 * 요청 하나가 데모인지 표시한다. SessionAuthFilter 가 세션 이메일을 보고 켜고, finally 에서 끈다.
 * 이 표시를 보고 JsonDocumentStore 가 문서 키를 갈라 실데이터와 섞이지 않게 한다.
 *
 * 백엔드에 @Async·스케줄러가 한 곳도 없어 요청이 스레드 하나에서 끝나므로 ThreadLocal 로 충분하다.
 * 나중에 비동기를 도입하면 그 스레드에서 이 값이 꺼져 데모 요청이 실데이터를 만지게 된다.
 * 그때 전파를 같이 넣어라.
 */
object DemoContext {
    /** 데모 세션의 예약 이메일. 이 값이 데모 표식이다. 로그인 허용 목록에는 절대 넣지 않는다. */
    const val EMAIL = "demo@studiowithai.local"
    const val KEY_PREFIX = "demo:"

    /** 세션 문서만은 갈라지면 안 된다. 갈리면 데모 세션 자체를 찾을 수 없다. */
    const val SHARED_KEY = "auth-sessions"

    private val state = ThreadLocal<String?>()

    fun set(sessionKey: String) = state.set(sessionKey)
    fun clear() = state.remove()
    fun isDemo(): Boolean = state.get() != null

    /** 세션별 실행 횟수를 세는 키. 데모가 아니면 null. */
    fun sessionKey(): String? = state.get()

    /** 문서 저장소를 안 타는 테이블(toon_image)의 격리 값. 서버가 정하고 클라이언트는 못 바꾼다. */
    fun owner(): String = if (isDemo()) "demo" else "owner"
}

/**
 * 데모가 부를 수 있는 모델 호출 횟수. LlmClient.chat() 한 곳에서만 센다.
 * 하루 상한은 비용 상한이고, 세션 상한은 한 방문자가 하루치를 다 쓰지 못하게 하는 장치다.
 *
 * ponytail: 프로세스 안의 카운터다. 인스턴스가 하나뿐이라 지금은 맞고, 재배포하면 0 으로 돌아간다.
 * 그게 문제되면 app_document 의 "demo:llm-budget" 문서로 옮겨라(하루치 카운터 한 줄).
 */
object DemoBudget {
    private var day: LocalDate = LocalDate.now()
    private var usedToday = 0
    private val usedBySession = mutableMapOf<String, Int>()
    private var imagesToday = 0
    private var demoImagesToday = 0
    private val demoImagesBySession = mutableMapOf<String, Int>()

    /**
     * 이미지 상한. 장수를 통째로 받아 4컷·8컷이 반쯤 통과하지 않게 한다.
     * sessionKey 가 null 이면 주인 요청이라 전체 상한만 본다. 주인에게도 상한이 필요한 이유는
     * 이미지가 장당 실제 돈이고 지금은 버튼 연타를 막을 게 아무것도 없어서다.
     * 데모 카운터를 따로 두는 이유는, 합치면 주인이 몇 장 쓴 순간 데모가 통째로 막히기 때문이다.
     */
    @Synchronized
    fun consumeImages(sessionKey: String?, count: Int, dailyLimit: Int, demoDailyLimit: Int, demoSessionLimit: Int) {
        rollDay()
        require(imagesToday + count <= dailyLimit) {
            "오늘 만들 수 있는 이미지 한도($dailyLimit 장)를 모두 썼습니다. 내일 다시 시도해 주세요."
        }
        if (sessionKey != null) {
            require(demoImagesToday + count <= demoDailyLimit) {
                "오늘 데모에서 만들 수 있는 이미지 한도($demoDailyLimit 장)를 모두 썼습니다. 내일 다시 시도해 주세요."
            }
            val used = demoImagesBySession[sessionKey] ?: 0
            require(used + count <= demoSessionLimit) {
                "이 데모 세션의 이미지 한도($demoSessionLimit 장)를 모두 썼습니다. 다른 기능을 둘러봐 주세요."
            }
            demoImagesBySession[sessionKey] = used + count
            demoImagesToday += count
        }
        imagesToday += count
    }

    @Synchronized
    fun consume(sessionKey: String, dailyLimit: Int, sessionLimit: Int) {
        rollDay()
        require(usedToday < dailyLimit) {
            "오늘 데모에서 쓸 수 있는 AI 실행 한도($dailyLimit 회)를 모두 썼습니다. 내일 다시 시도해 주세요."
        }
        val used = usedBySession[sessionKey] ?: 0
        require(used < sessionLimit) {
            "이 데모 세션에서 쓸 수 있는 AI 실행 한도($sessionLimit 회)를 모두 썼습니다. 다른 기능을 둘러봐 주세요."
        }
        usedToday++
        usedBySession[sessionKey] = used + 1
    }

    private fun rollDay() {
        val today = LocalDate.now()
        if (today == day) return
        day = today
        clearCounters()
    }

    @Synchronized
    fun reset() {
        day = LocalDate.now()
        clearCounters()
    }

    private fun clearCounters() {
        usedToday = 0
        usedBySession.clear()
        imagesToday = 0
        demoImagesToday = 0
        demoImagesBySession.clear()
    }
}
