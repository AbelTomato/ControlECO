package com.controleco.app.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import com.controleco.app.data.CategoryIcon
import com.controleco.app.data.db.AccBookDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object LedgerBackupRepository {
    private val imageName = Regex("cat_icon_[A-Za-z0-9_-]+\\.jpg")

    suspend fun createBackup(context: Context, db: AccBookDatabase, exportedAt: Long): LedgerBackup {
        val accounts = db.accountDao().getAll()
        val categories = db.categoryDao().getAll()
        val transactions = db.transactionDao().getAllForBackup()
        val merchantRules = db.merchantRuleDao().getAll()
        val imageNames = categories.mapNotNull { CategoryIcon.imageOf(it.icon) }.distinct()
        val images = imageNames.associateWith { name ->
            requireSafeImageName(name)
            val file = File(context.filesDir, name)
            require(file.isFile) { "Missing category image" }
            java.util.Base64.getEncoder().encodeToString(file.readBytes())
        }
        return LedgerBackup(
            LedgerBackupCodec.CURRENT_FORMAT_VERSION, exportedAt, accounts, categories,
            transactions, merchantRules, images,
        ).also { backup ->
            val errors = LedgerBackupValidator.validate(backup)
            require(errors.isEmpty()) { "Invalid ledger snapshot: ${errors.joinToString()}" }
        }
    }

    suspend fun restoreBackup(context: Context, db: AccBookDatabase, backup: LedgerBackup) {
        val errors = LedgerBackupValidator.validate(backup)
        require(errors.isEmpty()) { "Invalid ledger backup: ${errors.joinToString()}" }

        val staged = mutableListOf<File>()
        val installed = linkedMapOf<File, File?>()
        try {
            backup.imageFiles.forEach { (name, encoded) ->
                requireSafeImageName(name)
                val bytes = java.util.Base64.getDecoder().decode(encoded)
                val temp = File(context.filesDir, ".${UUID.randomUUID()}.tmp")
                FileOutputStream(temp).use { it.write(bytes) }
                require(BitmapFactory.decodeFile(temp.absolutePath) != null) {
                    "Invalid category image attachment"
                }
                staged += temp
            }
            val oldNames = (db.categoryDao().getAll().mapNotNull { CategoryIcon.imageOf(it.icon) }).toSet()
            val stagedByName = backup.imageFiles.keys.zip(staged).toMap()
            stagedByName.forEach { (name, temp) ->
                val destination = File(context.filesDir, name)
                val install = File(context.filesDir, ".$name.${UUID.randomUUID()}.new")
                check(temp.renameTo(install)) { "Unable to stage category image" }
                var previous: File? = null
                if (destination.exists()) {
                    previous = File(context.filesDir, ".$name.${UUID.randomUUID()}.old")
                    check(destination.renameTo(previous)) { "Unable to preserve category image" }
                }
                if (!install.renameTo(destination)) {
                    previous?.renameTo(destination)
                    error("Unable to install category image")
                }
                installed[destination] = previous
            }
            db.inLedgerTransaction {
                db.transactionDao().clearForRestore()
                db.merchantRuleDao().clearForRestore()
                db.categoryDao().clearForRestore()
                db.accountDao().clearForRestore()
                db.accountDao().insertAllForRestore(backup.accounts)
                db.categoryDao().insertAllForRestore(backup.categories)
                db.transactionDao().insertAllForRestore(backup.transactions)
                db.merchantRuleDao().insertAllForRestore(backup.merchantRules)
            }
            (oldNames - backup.imageFiles.keys).forEach { name ->
                if (imageName.matches(name) && backup.categories.none { CategoryIcon.imageOf(it.icon) == name }) File(context.filesDir, name).delete()
            }
        } catch (error: Throwable) {
            installed.forEach { (destination, previous) ->
                destination.delete()
                previous?.renameTo(destination)
            }
            throw error
        } finally {
            staged.forEach { it.delete() }
        }
        installed.values.filterNotNull().forEach { it.delete() }
    }

    private fun requireSafeImageName(name: String) {
        require(imageName.matches(name) && '/' !in name && '\\' !in name && ".." !in name) {
            "Invalid category image name"
        }
    }
}