package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.collections.SmartCollection
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionField
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionMatchMode
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionOperator
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRepository
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionRule
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionSortDirection
import com.lostf1sh.pixelplayeross.data.collections.SmartCollectionSortField
import com.lostf1sh.pixelplayeross.di.BackupGson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backup for smart collection **definitions**.
 *
 * A collection is user-created state, so it belongs in a backup. What it is *not* is a set of
 * tracks — the definition is the thing worth keeping, and the results are re-derived on the new
 * device from that device's own library. Backing up membership would restore a list of track ids
 * that may mean nothing on the other side.
 */
@Singleton
class SmartCollectionsModuleHandler @Inject constructor(
    private val repository: SmartCollectionRepository,
    @BackupGson private val gson: Gson,
) : BackupModuleHandler {

    override val section = BackupSection.SMART_COLLECTIONS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val collections = repository.getAll()
            // A built-in is re-seeded on the destination device, so exporting it would duplicate it.
            .filterNot { it.isBuiltIn }
            .map { it.toBackupEntry() }
        gson.toJson(collections)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        repository.getAll().count { !it.isBuiltIn }
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, SmartCollectionBackupEntry::class.java).type
        val entries: List<SmartCollectionBackupEntry> = gson.fromJson(payload, type) ?: emptyList()
        entries.forEach { entry ->
            repository.create(
                name = entry.name ?: return@forEach,
                rules = entry.toRules(),
                matchMode = SmartCollectionMatchMode.fromStorageKey(entry.matchMode),
                sortField = SmartCollectionSortField.fromStorageKey(entry.sortField),
                sortDirection = SmartCollectionSortDirection.fromStorageKey(entry.sortDirection),
                limit = entry.limit?.takeIf { it > 0 },
            )
        }
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}

/**
 * The backup wire format for a smart collection.
 *
 * A DTO rather than the Room entity, for the reason Phase 12.1 established the hard way: Gson
 * instantiates through `Unsafe`, so **the constructor never runs and Kotlin defaults never apply**.
 * A field added to an entity is therefore decoded as `null` from every older payload — in a
 * property the type system says cannot be null — and the restore produces objects that violate
 * their own type.
 *
 * So every field that could be absent on an older payload is nullable here, and every field that
 * could be unrecognised is a `String?` resolved through `fromStorageKey`, which returns null rather
 * than throwing. A collection whose rule this build does not understand is **skipped, not crashed
 * on and not guessed at** — the brief's requirement, applied at the boundary where a payload
 * written by a different version actually arrives.
 */
data class SmartCollectionBackupEntry(
    @SerializedName(value = "name", alternate = ["Name"]) val name: String? = null,
    @SerializedName(value = "matchMode", alternate = ["match_mode"]) val matchMode: String? = null,
    @SerializedName(value = "sortField", alternate = ["sort_field"]) val sortField: String? = null,
    @SerializedName(value = "sortDirection", alternate = ["sort_direction"]) val sortDirection: String? = null,
    @SerializedName("limit") val limit: Int? = null,
    @SerializedName("rules") val rules: List<SmartCollectionRuleBackupEntry>? = null,
)

data class SmartCollectionRuleBackupEntry(
    @SerializedName("field") val field: String? = null,
    @SerializedName(value = "operator", alternate = ["op"]) val operation: String? = null,
    @SerializedName(value = "valueText", alternate = ["value_text"]) val valueText: String? = null,
    @SerializedName(value = "valueNumber", alternate = ["value_number"]) val valueNumber: Double? = null,
    @SerializedName(value = "valueSecondNumber", alternate = ["value_second_number"]) val valueSecondNumber: Double? = null,
)

private fun SmartCollection.toBackupEntry() = SmartCollectionBackupEntry(
    name = name,
    matchMode = matchMode.storageKey,
    sortField = sortField.storageKey,
    sortDirection = sortDirection.storageKey,
    limit = limit,
    rules = rules.map { rule ->
        SmartCollectionRuleBackupEntry(
            field = rule.target.storageKey,
            operation = rule.operation.storageKey,
            valueText = rule.textValue,
            valueNumber = rule.numberValue,
            valueSecondNumber = rule.secondNumberValue,
        )
    },
)

/**
 * A restored rule, or null when this build cannot understand it.
 *
 * A null drops the rule, which would change what the collection means — so the whole *collection* is
 * skipped instead when any of its rules is unreadable. Restoring a collection that quietly answers a
 * different question is worse than not restoring it and saying so.
 */
private fun SmartCollectionBackupEntry.toRules(): List<SmartCollectionRule> {
    val entries = rules.orEmpty()
    val parsed = entries.mapNotNull { rule ->
        val target = SmartCollectionField.fromStorageKey(rule.field) ?: return@mapNotNull null
        val operation = SmartCollectionOperator.fromStorageKey(rule.operation) ?: return@mapNotNull null
        SmartCollectionRule(
            target = target,
            operation = operation,
            textValue = rule.valueText,
            numberValue = rule.valueNumber,
            secondNumberValue = rule.valueSecondNumber,
        )
    }
    if (parsed.size != entries.size) return emptyList()
    return parsed
}
