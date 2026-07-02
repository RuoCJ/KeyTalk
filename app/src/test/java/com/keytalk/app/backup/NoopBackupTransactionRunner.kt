package com.keytalk.app.backup

object NoopBackupTransactionRunner : BackupTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
}
