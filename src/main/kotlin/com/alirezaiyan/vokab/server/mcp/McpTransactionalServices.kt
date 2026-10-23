package com.alirezaiyan.vokab.server.mcp

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * SAS's JDBC services look up the registered client while they are still reading the authorization
 * row, which takes a second pool connection inside the first. With a few `/mcp` calls at once every
 * connection ends up held by a lookup waiting for another one, and the pool deadlocks; on 2026-10-15
 * that froze the whole server. Inside one transaction the nested lookup reuses the connection.
 */
internal class McpTransactions(transactionManager: PlatformTransactionManager) {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writes = TransactionTemplate(transactionManager)

    fun <T> read(block: () -> T): T? = reads.execute { block() }

    fun write(block: () -> Unit) = writes.executeWithoutResult { block() }
}

internal class TransactionalAuthorizationService(
    private val delegate: OAuth2AuthorizationService,
    private val transactions: McpTransactions,
) : OAuth2AuthorizationService {
    override fun save(authorization: OAuth2Authorization) = transactions.write { delegate.save(authorization) }

    override fun remove(authorization: OAuth2Authorization) = transactions.write { delegate.remove(authorization) }

    override fun findById(id: String): OAuth2Authorization? = transactions.read { delegate.findById(id) }

    override fun findByToken(token: String, tokenType: OAuth2TokenType?): OAuth2Authorization? =
        transactions.read { delegate.findByToken(token, tokenType) }
}

internal class TransactionalAuthorizationConsentService(
    private val delegate: OAuth2AuthorizationConsentService,
    private val transactions: McpTransactions,
) : OAuth2AuthorizationConsentService {
    override fun save(consent: OAuth2AuthorizationConsent) = transactions.write { delegate.save(consent) }

    override fun remove(consent: OAuth2AuthorizationConsent) = transactions.write { delegate.remove(consent) }

    override fun findById(registeredClientId: String, principalName: String): OAuth2AuthorizationConsent? =
        transactions.read { delegate.findById(registeredClientId, principalName) }
}
