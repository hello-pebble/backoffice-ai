package com.backoffice.dashboard

import com.google.api.client.util.store.AbstractDataStoreFactory
import com.google.api.client.util.store.AbstractMemoryDataStore
import com.google.api.client.util.store.DataStore
import org.springframework.stereotype.Component
import java.io.Serializable
import java.util.Base64

/**
 * Gmail OAuth 토큰을 컨테이너 파일시스템 대신 Postgres(app_documents)에 보관한다.
 * 기본 구현인 FileDataStoreFactory를 쓰면 재배포마다 토큰이 사라져 매번 재인증해야 한다.
 */
@Component
class PostgresDataStoreFactory(private val documents: JsonDocumentStore) : AbstractDataStoreFactory() {
    override fun <V : Serializable> createDataStore(id: String): DataStore<V> =
        PostgresDataStore(this, id, documents)
}

class PostgresDataStore<V : Serializable>(
    factory: PostgresDataStoreFactory,
    id: String,
    private val documents: JsonDocumentStore,
) : AbstractMemoryDataStore<V>(factory, id) {

    private val documentKey = "gmail-token-$id"

    init {
        // 부모 생성자가 keyValueMap을 만든 뒤 실행된다. 저장된 토큰을 메모리 맵으로 복원한다.
        documents.read(documentKey, StoredTokens::class.java)?.entries?.forEach { (key, encoded) ->
            keyValueMap[key] = Base64.getDecoder().decode(encoded)
        }
    }

    /** 부모의 set/delete/clear가 변경 직후 호출한다. */
    override fun save() {
        val encoded = keyValueMap.mapValues { Base64.getEncoder().encodeToString(it.value) }
        documents.write(documentKey, StoredTokens(encoded))
    }
}

/** 토큰은 임의 바이트열이라 jsonb에 넣기 위해 Base64 문자열로 감싼다. */
data class StoredTokens(val entries: Map<String, String> = emptyMap())
