package com.lostf1sh.pixelplayeross.data.collections

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update

/**
 * A saved collection definition.
 *
 * This table holds **rules, never results**. Membership is a view over current library state, so
 * there is nothing to re-generate after a scan, a play, a favourite or a dormancy — the next read
 * is simply correct. Persisting the matching rows would create a second copy of the library to keep
 * in step, and every sync would then have to reconcile the two.
 *
 * [name] is the only free text, and it is never interpreted.
 */
@Entity(
    tableName = "smart_collections",
    indices = [Index(value = ["name"], unique = false)],
)
data class SmartCollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    /** `all` or `any`. An unrecognised value reads as `all`, the narrower of the two. */
    @ColumnInfo(name = "match_mode", defaultValue = "'all'") val matchMode: String = "all",
    @ColumnInfo(name = "sort_field", defaultValue = "'title'") val sortField: String = "title",
    @ColumnInfo(name = "sort_direction", defaultValue = "'asc'") val sortDirection: String = "asc",
    @ColumnInfo(name = "result_limit") val resultLimit: Int? = null,
    @ColumnInfo(name = "is_built_in", defaultValue = "0") val isBuiltIn: Boolean = false,
    /**
     * The definition this built-in was seeded from, so re-seeding does not duplicate it.
     *
     * Null for a user-created collection.
     */
    @ColumnInfo(name = "built_in_key") val builtInKey: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One rule of one collection.
 *
 * The operands are three typed columns rather than one serialized blob: a single blob would be
 * opaque to the database and would make a future migration of the *value* shape impossible without
 * parsing user data in SQL. `value_number` covers counts, years and epoch-millis dates;
 * `value_second_number` exists solely for `between`.
 *
 * [field] and [operator] hold the explicit storage keys from the model, never Kotlin class or enum
 * names. A refactor that renames a constant must not make a user's saved collection unreadable, and
 * [SmartCollectionOperator.fromStorageKey] returning null is what lets an unknown future operator
 * fail conservatively instead of crashing.
 */
@Entity(
    tableName = "smart_collection_rules",
    foreignKeys = [
        ForeignKey(
            entity = SmartCollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["collection_id"], unique = false)],
)
data class SmartCollectionRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "collection_id") val collectionId: Long,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "field") val field: String,
    @ColumnInfo(name = "operator") val operator: String,
    @ColumnInfo(name = "value_text") val valueText: String? = null,
    @ColumnInfo(name = "value_number") val valueNumber: Double? = null,
    @ColumnInfo(name = "value_second_number") val valueSecondNumber: Double? = null,
)

/** A collection with its rules, as Room reads them together. */
data class SmartCollectionWithRules(
    @Embedded val collection: SmartCollectionEntity,
    @Relation(parentColumn = "id", entityColumn = "collection_id")
    val rules: List<SmartCollectionRuleEntity>,
)

@Dao
interface SmartCollectionDao {

    @Transaction
    @Query("SELECT * FROM smart_collections ORDER BY is_built_in DESC, name COLLATE NOCASE ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<SmartCollectionWithRules>>

    @Transaction
    @Query("SELECT * FROM smart_collections ORDER BY is_built_in DESC, name COLLATE NOCASE ASC")
    suspend fun getAll(): List<SmartCollectionWithRules>

    @Transaction
    @Query("SELECT * FROM smart_collections WHERE id = :id")
    suspend fun getById(id: Long): SmartCollectionWithRules?

    @Query("SELECT * FROM smart_collections WHERE built_in_key = :key LIMIT 1")
    suspend fun getByBuiltInKey(key: String): SmartCollectionEntity?

    @Insert
    suspend fun insertCollection(collection: SmartCollectionEntity): Long

    @Update
    suspend fun updateCollection(collection: SmartCollectionEntity)

    @Query("DELETE FROM smart_collections WHERE id = :id AND is_built_in = 0")
    suspend fun deleteCollection(id: Long)

    @Insert
    suspend fun insertRules(rules: List<SmartCollectionRuleEntity>)

    @Query("DELETE FROM smart_collection_rules WHERE collection_id = :collectionId")
    suspend fun deleteRulesFor(collectionId: Long)

    /**
     * Replaces a collection's rules atomically.
     *
     * One transaction so a collection can never be observed with half of an edited rule set — which
     * would silently mean something different from both the old and the new definition.
     */
    @Transaction
    suspend fun replaceRules(collectionId: Long, rules: List<SmartCollectionRuleEntity>) {
        deleteRulesFor(collectionId)
        if (rules.isNotEmpty()) insertRules(rules)
    }

    @Query("SELECT COUNT(*) FROM smart_collections")
    suspend fun count(): Int
}

// --- mapping -----------------------------------------------------------------------------------------

/**
 * Entity to model.
 *
 * **Unknown storage keys become an unsupported rule rather than an exception.** A collection saved
 * by a newer build, or one whose rule type was removed, must still open — the brief is explicit that
 * an unrecognised rule fails conservatively and is reported, never crashes and never silently
 * reinterpreted. The rule is kept with its raw keys so the diagnostic can say exactly what it could
 * not understand.
 */
fun SmartCollectionWithRules.toModel(): SmartCollection {
    val ordered = rules.sortedBy { it.position }
    val parsed = ordered.mapNotNull { it.toModel() }
    val unparsed = ordered
        .filter { entity -> SmartCollectionField.fromStorageKey(entity.field) == null || SmartCollectionOperator.fromStorageKey(entity.operator) == null }
        .map { entity -> "${entity.field} ${entity.operator}" }

    return SmartCollection(
        id = collection.id,
        name = collection.name,
        matchMode = SmartCollectionMatchMode.fromStorageKey(collection.matchMode),
        rules = parsed,
        unparsedRules = unparsed,
        sortField = SmartCollectionSortField.fromStorageKey(collection.sortField),
        sortDirection = SmartCollectionSortDirection.fromStorageKey(collection.sortDirection),
        limit = collection.resultLimit?.takeIf { it > 0 },
        createdAt = collection.createdAt,
        updatedAt = collection.updatedAt,
        isBuiltIn = collection.isBuiltIn,
    )
}

/**
 * One rule, or null when it cannot be understood at all.
 *
 * A null return removes the rule from the model, which would silently change the collection's
 * meaning — so callers must treat "rules in the database ≠ rules in the model" as a fault. The
 * repository surfaces that as an invalid collection rather than pretending it is fine.
 */
private fun SmartCollectionRuleEntity.toModel(): SmartCollectionRule? {
    val parsedField = SmartCollectionField.fromStorageKey(field) ?: return null
    val parsedOperation = SmartCollectionOperator.fromStorageKey(operator) ?: return null
    return SmartCollectionRule(
        id = id,
        target = parsedField,
        operation = parsedOperation,
        textValue = valueText,
        numberValue = valueNumber,
        secondNumberValue = valueSecondNumber,
        position = position,
    )
}

/** Model to entity. [SmartCollection.id] of zero means "insert me". */
fun SmartCollection.toEntity(): SmartCollectionEntity = SmartCollectionEntity(
    id = id,
    name = name.trim(),
    matchMode = matchMode.storageKey,
    sortField = sortField.storageKey,
    sortDirection = sortDirection.storageKey,
    resultLimit = limit,
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun SmartCollection.toRuleEntities(collectionId: Long): List<SmartCollectionRuleEntity> =
    rules.mapIndexed { index, rule ->
        SmartCollectionRuleEntity(
            id = 0L,
            collectionId = collectionId,
            position = index,
            field = rule.target.storageKey,
            operator = rule.operation.storageKey,
            valueText = rule.textValue,
            valueNumber = rule.numberValue,
            valueSecondNumber = rule.secondNumberValue,
        )
    }
