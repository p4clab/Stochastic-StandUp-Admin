package iclab.kaist.common

import com.google.cloud.firestore.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KProperty
import kotlin.reflect.full.primaryConstructor


@Suppress("UNCHECKED_CAST")
abstract class DataField<T : Any>(val name: String, private val default: T) {
    abstract fun fromSnapshot(value: DocumentSnapshot): T?

    operator fun getValue(thisRef: DocumentEntity, property: KProperty<*>): T =
        (thisRef.readValues[this] as? T) ?: default

    operator fun setValue(thisRef: DocumentEntity, property: KProperty<*>, value: T) {
        value.let { thisRef.writeValues[this] = it }
    }
}

class IntField(name: String, default: Int) : DataField<Int>(name, default) {
    override fun fromSnapshot(value: DocumentSnapshot): Int? = value.getLong(name)?.toInt()
}

class LongField(name: String, default: Long) : DataField<Long>(name, default) {
    override fun fromSnapshot(value: DocumentSnapshot): Long? = value.getLong(name)
}

class FloatField(name: String, default: Float) : DataField<Float>(name, default) {
    override fun fromSnapshot(value: DocumentSnapshot): Float? = value.getDouble(name)?.toFloat()
}

class DoubleField(name: String, default: Double) : DataField<Double>(name, default) {
    override fun fromSnapshot(value: DocumentSnapshot): Double? = value.getDouble(name)
}

class StringField(name: String, default: String) : DataField<String>(name, default) {
    override fun fromSnapshot(value: DocumentSnapshot): String? = value.getString(name)
}

class BooleanField(name: String, default: Boolean) : DataField<Boolean>(name, default) {
    override fun fromSnapshot(value: DocumentSnapshot): Boolean? = value.getBoolean(name) ?: false
}

class TimestampField(private val type: Int) : DataField<Long>("timestamp", 0) {
    override fun fromSnapshot(value: DocumentSnapshot): Long? = when(type) {
        TYPE_CREATE_TIME -> (value.createTime?.seconds ?: 0) * 1000
        else -> (value.updateTime?.seconds ?: 0) * 1000
    }

    companion object {
        const val TYPE_CREATE_TIME = 0x1
        const val TYPE_UPDATE_TIME = 0x2
    }
}

abstract class Documents {
    internal val fields = mutableListOf<DataField<*>>()

    private fun <T : Any> registerField(field: DataField<T>): DataField<T> {
        fields.add(field)
        return field
    }

    fun integer(name: String, default: Int) = registerField(IntField(name, default))

    fun long(name: String, default: Long) = registerField(LongField(name, default))

    fun float(name: String, default: Float) = registerField(FloatField(name, default))

    fun double(name: String, default: Double) = registerField(DoubleField(name, default))

    fun string(name: String, default: String) = registerField(StringField(name, default))

    fun boolean(name: String, default: Boolean) = registerField(BooleanField(name, default))

}

abstract class DocumentEntity {
    internal val readValues: MutableMap<DataField<*>, Any> = mutableMapOf()
    internal val writeValues: MutableMap<DataField<*>, Any> = mutableMapOf()

    var id: String = ""
    var createTime: Long = 0
    var updateTime: Long = 0
}


class QueryBuilder(private val ref: CollectionReference) {
    internal var query: Query? = null

    infix fun <T : Any> DataField<T>.greaterThan(value: T) {
        query = query?.whereGreaterThan(name, value) ?: ref.whereGreaterThan(name, value)
    }

    infix fun <T : Any> DataField<T>.greaterThanOrEqualTo(value: T) {
        query = query?.whereGreaterThanOrEqualTo(name, value) ?: ref.whereGreaterThanOrEqualTo(
            name,
            value
        )
    }

    infix fun <T : Any> DataField<T>.lessThan(value: T) {
        query = query?.whereLessThan(name, value) ?: ref.whereLessThan(name, value)
    }

    infix fun <T : Any> DataField<T>.lessThanOrEqualTo(value: T) {
        query =
            query?.whereLessThanOrEqualTo(name, value) ?: ref.whereLessThanOrEqualTo(name, value)
    }

    infix fun <T : Any> DataField<T>.equalTo(value: T?) {
        query = query?.whereEqualTo(name, value) ?: ref.whereEqualTo(name, value)
    }

    infix fun <T : Any> DataField<T>.isOneOf(value: List<T?>) {
        query = query?.whereIn(name, value) ?: ref.whereIn(name, value)
    }
}

@Suppress("UNCHECKED_CAST")
abstract class DocumentEntityClass<T : DocumentEntity>(
    private val documents: Documents,
    type: Class<T>? = null
) {
    private val klass: Class<*> = type ?: javaClass.enclosingClass as Class<T>
    private val constructor = klass.kotlin.primaryConstructor!!

    suspend fun create(ref: CollectionReference, id: String? = null, context: CoroutineContext = EmptyCoroutineContext, body: T.() -> Unit): String? {
        val instance = constructor.call() as T
        instance.body()
        val data = documents.fields.associate { field ->
            field.name to instance.writeValues[field]
        }

        return if (!id.isNullOrBlank()) {
            ref.document(id).set(data).asSuspend(context)
            id
        } else {
            ref.add(data).asSuspend()?.id
        }
    }

    suspend fun update(ref: CollectionReference, id: String, context: CoroutineContext = EmptyCoroutineContext, body: T.() -> Unit) {
        val instance = constructor.call() as T
        instance.body()
        val data = instance.writeValues.mapKeys { it.key.name }
        ref.document(id).set(data, SetOptions.merge()).asSuspend(context)
    }

    suspend fun update(ref: CollectionReference, id: String, context: CoroutineContext = EmptyCoroutineContext, vararg params: Pair<DataField<*>, Any?>) {
        val data = params.associate { (k, v) -> k.name to v }
        ref.document(id).set(data, SetOptions.merge()).asSuspend(context)
    }

    suspend fun updateSelf(ref: DocumentReference, context: CoroutineContext = EmptyCoroutineContext, body: T.() -> Unit) {
        val instance = constructor.call() as T
        instance.body()
        val data = instance.writeValues.mapKeys { it.key.name }
        ref.set(data, SetOptions.merge()).asSuspend(context)
    }

    suspend fun updateSelf(ref: DocumentReference, context: CoroutineContext = EmptyCoroutineContext, vararg params: Pair<DataField<*>, Any?>) {
        val data = params.associate { (k, v) -> k.name to v }
        ref.set(data, SetOptions.merge()).asSuspend(context)
    }

    suspend fun get(ref: CollectionReference, id: String, context: CoroutineContext = EmptyCoroutineContext): T? {
        val snapshot = ref.document(id).get().asSuspend(context) ?: return null
        if (!snapshot.exists()) return null
        return fromDocumentSnapshot(snapshot)
    }

    fun fromDocumentSnapshot(snapshot: DocumentSnapshot) : T {
        val instance = constructor.call() as T

        instance.id = snapshot.id
        instance.createTime = (snapshot.createTime?.seconds ?: 0) * 1000
        instance.updateTime = (snapshot.updateTime?.seconds ?: 0) * 1000

        documents.fields.forEach { field ->
            field.fromSnapshot(snapshot)?.let { instance.readValues[field] = it }
        }

        return instance
    }

    suspend fun getSelf(ref: DocumentReference, context: CoroutineContext = EmptyCoroutineContext) : T? {
        val data = ref.get().asSuspend(context) ?: return null
        return fromDocumentSnapshot(data)
    }

    suspend fun select(
        ref: CollectionReference,
        orderBy: DataField<*>? = null,
        isAscending: Boolean = true,
        limit: Int? = null,
        context: CoroutineContext = EmptyCoroutineContext,
        body: (QueryBuilder.() -> Unit)? = null
    ): List<T> {
        val query = buildQuery(ref, orderBy, isAscending, limit, body)
        val task = query?.get() ?: ref.get()

        return task.asSuspend(context)?.documents?.map { snapshot ->
            fromDocumentSnapshot(snapshot)
        } ?: listOf()
    }

    fun buildQuery(
        ref: CollectionReference,
        orderBy: DataField<*>? = null,
        isAscending: Boolean = true,
        limit: Int? = null,
        body: (QueryBuilder.() -> Unit)? = null
    ) : Query? {
        var query: Query? = if (body != null) {
            val builder = QueryBuilder(ref)
            builder.body()
            builder.query
        } else {
            null
        }

        if (orderBy != null) query = query?.orderBy(
            orderBy.name,
            if (isAscending) Query.Direction.ASCENDING else Query.Direction.DESCENDING
        ) ?: ref.orderBy(
            orderBy.name,
            if (isAscending) Query.Direction.ASCENDING else Query.Direction.DESCENDING
        )
        if (limit != null) query = query?.limit(limit) ?: ref.limit(limit)

        return query
    }
}