package net.corda.core.serialization

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.io.serialization.kryo.KryoSerializer
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Kryo.DefaultInstantiatorStrategy
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.JavaSerializer
import com.esotericsoftware.kryo.serializers.MapSerializer
import de.javakaffee.kryoserializers.ArraysAsListSerializer
import de.javakaffee.kryoserializers.guava.*
import net.corda.core.contracts.*
import net.corda.core.crypto.*
import net.corda.core.node.AttachmentsClassLoader
import net.corda.core.node.services.AttachmentStorage
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.NonEmptySet
import net.corda.core.utilities.NonEmptySetSerializer
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.objenesis.strategy.StdInstantiatorStrategy
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import javax.annotation.concurrent.ThreadSafe
import kotlin.reflect.*
import kotlin.reflect.jvm.javaType

/**
 * Serialization utilities, using the Kryo framework with a custom serialiser for immutable data classes and a dead
 * simple, totally non-extensible binary (sub)format.
 *
 * This is NOT what should be used in any final platform product, rather, the final state should be a precisely
 * specified and standardised binary format with attention paid to anti-malleability, versioning and performance.
 * FIX SBE is a potential candidate: it prioritises performance over convenience and was designed for HFT. Google
 * Protocol Buffers with a minor tightening to make field reordering illegal is another possibility.
 *
 * FIX SBE:
 *     https://real-logic.github.io/simple-binary-encoding/
 *     http://mechanical-sympathy.blogspot.co.at/2014/05/simple-binary-encoding.html
 * Protocol buffers:
 *     https://developers.google.com/protocol-buffers/
 *
 * But for now we use Kryo to maximise prototyping speed.
 *
 * Note that this code ignores *ALL* concerns beyond convenience, in particular it ignores:
 *
 * - Performance
 * - Security
 *
 * This code will happily deserialise literally anything, including malicious streams that would reconstruct classes
 * in invalid states, thus violating system invariants. It isn't designed to handle malicious streams and therefore,
 * isn't usable beyond the prototyping stage. But that's fine: we can revisit serialisation technologies later after
 * a formal evaluation process.
 */

// A convenient instance of Kryo pre-configured with some useful things. Used as a default by various functions.
val THREAD_LOCAL_KRYO = ThreadLocal.withInitial { createKryo() }

/**
 * A type safe wrapper around a byte array that contains a serialised object. You can call [SerializedBytes.deserialize]
 * to get the original object back.
 */
class SerializedBytes<T : Any>(bytes: ByteArray) : OpaqueBytes(bytes) {
    // It's OK to use lazy here because SerializedBytes is configured to use the ImmutableClassSerializer.
    val hash: SecureHash by lazy { bytes.sha256() }

    fun writeToFile(path: Path) = Files.write(path, bytes)
}

// Some extension functions that make deserialisation convenient and provide auto-casting of the result.
fun <T : Any> ByteArray.deserialize(kryo: Kryo = THREAD_LOCAL_KRYO.get()): T {
    @Suppress("UNCHECKED_CAST")
    return kryo.readClassAndObject(Input(this)) as T
}

fun <T : Any> OpaqueBytes.deserialize(kryo: Kryo = THREAD_LOCAL_KRYO.get()): T {
    return this.bytes.deserialize(kryo)
}

// The more specific deserialize version results in the bytes being cached, which is faster.
@JvmName("SerializedBytesWireTransaction")
fun SerializedBytes<WireTransaction>.deserialize(kryo: Kryo = THREAD_LOCAL_KRYO.get()): WireTransaction = WireTransaction.deserialize(this, kryo)

fun <T : Any> SerializedBytes<T>.deserialize(kryo: Kryo = THREAD_LOCAL_KRYO.get()): T = bytes.deserialize(kryo)

/**
 * A serialiser that avoids writing the wrapper class to the byte stream, thus ensuring [SerializedBytes] is a pure
 * type safety hack.
 */
object SerializedBytesSerializer : Serializer<SerializedBytes<Any>>() {
    override fun write(kryo: Kryo, output: Output, obj: SerializedBytes<Any>) {
        output.writeVarInt(obj.bytes.size, true)
        output.writeBytes(obj.bytes)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<SerializedBytes<Any>>): SerializedBytes<Any> {
        return SerializedBytes(input.readBytes(input.readVarInt(true)))
    }
}

/**
 * Can be called on any object to convert it to a byte array (wrapped by [SerializedBytes]), regardless of whether
 * the type is marked as serializable or was designed for it (so be careful!).
 */
fun <T : Any> T.serialize(kryo: Kryo = THREAD_LOCAL_KRYO.get()): SerializedBytes<T> {
    val stream = ByteArrayOutputStream()
    Output(stream).use {
        kryo.writeClassAndObject(it, this)
    }
    return SerializedBytes(stream.toByteArray())
}

/**
 * Serializes properties and deserializes by using the constructor. This assumes that all backed properties are
 * set via the constructor and the class is immutable.
 */
class ImmutableClassSerializer<T : Any>(val klass: KClass<T>) : Serializer<T>() {
    val props = klass.memberProperties.sortedBy { it.name }
    val propsByName = props.associateBy { it.name }
    val constructor = klass.primaryConstructor!!

    init {
        // Verify that this class is immutable (all properties are final)
        assert(props.none { it is KMutableProperty<*> })
    }

    // Just a utility to help us catch cases where nodes are running out of sync versions.
    private fun hashParameters(params: List<KParameter>): Int {
        return params.map {
            (it.name ?: "") + it.index.toString() + it.type.javaType.typeName
        }.hashCode()
    }

    override fun write(kryo: Kryo, output: Output, obj: T) {
        output.writeVarInt(constructor.parameters.size, true)
        output.writeInt(hashParameters(constructor.parameters))
        for (param in constructor.parameters) {
            val kProperty = propsByName[param.name!!]!!
            when (param.type.javaType.typeName) {
                "int" -> output.writeVarInt(kProperty.get(obj) as Int, true)
                "long" -> output.writeVarLong(kProperty.get(obj) as Long, true)
                "short" -> output.writeShort(kProperty.get(obj) as Int)
                "char" -> output.writeChar(kProperty.get(obj) as Char)
                "byte" -> output.writeByte(kProperty.get(obj) as Byte)
                "double" -> output.writeDouble(kProperty.get(obj) as Double)
                "float" -> output.writeFloat(kProperty.get(obj) as Float)
                else -> try {
                    kryo.writeClassAndObject(output, kProperty.get(obj))
                } catch (e: Exception) {
                    throw IllegalStateException("Failed to serialize ${param.name} in ${klass.qualifiedName}", e)
                }
            }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<T>): T {
        assert(type.kotlin == klass)
        val numFields = input.readVarInt(true)
        val fieldTypeHash = input.readInt()

        // A few quick checks for data evolution. Note that this is not guaranteed to catch every problem! But it's
        // good enough for a prototype.
        if (numFields != constructor.parameters.size)
            throw KryoException("Mismatch between number of constructor parameters and number of serialised fields " +
                    "for ${klass.qualifiedName} ($numFields vs ${constructor.parameters.size})")
        if (fieldTypeHash != hashParameters(constructor.parameters))
            throw KryoException("Hashcode mismatch for parameter types for ${klass.qualifiedName}: unsupported type evolution has happened.")

        val args = arrayOfNulls<Any?>(numFields)
        var cursor = 0
        for (param in constructor.parameters) {
            args[cursor++] = when (param.type.javaType.typeName) {
                "int" -> input.readVarInt(true)
                "long" -> input.readVarLong(true)
                "short" -> input.readShort()
                "char" -> input.readChar()
                "byte" -> input.readByte()
                "double" -> input.readDouble()
                "float" -> input.readFloat()
                else -> kryo.readClassAndObject(input)
            }
        }
        // If the constructor throws an exception, pass it through instead of wrapping it.
        return try {
            constructor.call(*args)
        } catch (e: InvocationTargetException) {
            throw e.cause!!
        }
    }
}

inline fun <T> Kryo.useClassLoader(cl: ClassLoader, body: () -> T): T {
    val tmp = this.classLoader ?: ClassLoader.getSystemClassLoader()
    this.classLoader = cl
    try {
        return body()
    } finally {
        this.classLoader = tmp
    }
}

fun Output.writeBytesWithLength(byteArray: ByteArray) {
    this.writeInt(byteArray.size, true)
    this.writeBytes(byteArray)
}

fun Input.readBytesWithLength(): ByteArray {
    val size = this.readInt(true)
    return this.readBytes(size)
}

/** Thrown during deserialisation to indicate that an attachment needed to construct the [WireTransaction] is not found */
class MissingAttachmentsException(val ids: List<SecureHash>) : Exception()

/** A serialisation engine that knows how to deserialise code inside a sandbox */
@ThreadSafe
object WireTransactionSerializer : Serializer<WireTransaction>() {
    override fun write(kryo: Kryo, output: Output, obj: WireTransaction) {
        kryo.writeClassAndObject(output, obj.inputs)
        kryo.writeClassAndObject(output, obj.attachments)
        kryo.writeClassAndObject(output, obj.outputs)
        kryo.writeClassAndObject(output, obj.commands)
        kryo.writeClassAndObject(output, obj.notary)
        kryo.writeClassAndObject(output, obj.mustSign)
        kryo.writeClassAndObject(output, obj.type)
        kryo.writeClassAndObject(output, obj.timestamp)
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<WireTransaction>): WireTransaction {
        val inputs = kryo.readClassAndObject(input) as List<StateRef>
        val attachmentHashes = kryo.readClassAndObject(input) as List<SecureHash>

        // If we're deserialising in the sandbox context, we use our special attachments classloader.
        // Otherwise we just assume the code we need is on the classpath already.
        val attachmentStorage = kryo.attachmentStorage
        val classLoader = if (attachmentStorage != null) {
            val missing = ArrayList<SecureHash>()
            val attachments = ArrayList<Attachment>()
            for (id in attachmentHashes) {
                val attachment = attachmentStorage.openAttachment(id)
                if (attachment == null)
                    missing += id
                else
                    attachments += attachment
            }
            if (missing.isNotEmpty())
                throw MissingAttachmentsException(missing)
            AttachmentsClassLoader(attachments)
        } else javaClass.classLoader

        kryo.useClassLoader(classLoader) {
            val outputs = kryo.readClassAndObject(input) as List<TransactionState<ContractState>>
            val commands = kryo.readClassAndObject(input) as List<Command>
            val notary = kryo.readClassAndObject(input) as Party?
            val signers = kryo.readClassAndObject(input) as List<CompositeKey>
            val transactionType = kryo.readClassAndObject(input) as TransactionType
            val timestamp = kryo.readClassAndObject(input) as Timestamp?

            return WireTransaction(inputs, attachmentHashes, outputs, commands, notary, signers, transactionType, timestamp)
        }
    }
}

/** For serialising an ed25519 private key */
@ThreadSafe
object Ed25519PrivateKeySerializer : Serializer<EdDSAPrivateKey>() {
    override fun write(kryo: Kryo, output: Output, obj: EdDSAPrivateKey) {
        check(obj.params == ed25519Curve)
        output.writeBytesWithLength(obj.seed)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<EdDSAPrivateKey>): EdDSAPrivateKey {
        val seed = input.readBytesWithLength()
        return EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, ed25519Curve))
    }
}

/** For serialising an ed25519 public key */
@ThreadSafe
object Ed25519PublicKeySerializer : Serializer<EdDSAPublicKey>() {
    override fun write(kryo: Kryo, output: Output, obj: EdDSAPublicKey) {
        check(obj.params == ed25519Curve)
        output.writeBytesWithLength(obj.abyte)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<EdDSAPublicKey>): EdDSAPublicKey {
        val A = input.readBytesWithLength()
        return EdDSAPublicKey(EdDSAPublicKeySpec(A, ed25519Curve))
    }
}

/** Marker interface for kotlin object definitions so that they are deserialized as the singleton instance. */
interface DeserializeAsKotlinObjectDef

/** Serializer to deserialize kotlin object definitions marked with [DeserializeAsKotlinObjectDef]. */
object KotlinObjectSerializer : Serializer<DeserializeAsKotlinObjectDef>() {
    override fun read(kryo: Kryo, input: Input, type: Class<DeserializeAsKotlinObjectDef>): DeserializeAsKotlinObjectDef {
        // read the public static INSTANCE field that kotlin compiler generates.
        return type.getField("INSTANCE").get(null) as DeserializeAsKotlinObjectDef
    }

    override fun write(kryo: Kryo, output: Output, obj: DeserializeAsKotlinObjectDef) {
    }
}

fun createKryo(k: Kryo = Kryo()): Kryo {
    return k.apply {
        // Allow any class to be deserialized (this is insecure but for prototyping we don't care)
        isRegistrationRequired = false
        // Allow construction of objects using a JVM backdoor that skips invoking the constructors, if there is no
        // no-arg constructor available.
        instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())

        register(Arrays.asList("").javaClass, ArraysAsListSerializer())

        // Because we like to stick a Kryo object in a ThreadLocal to speed things up a bit, we can end up trying to
        // serialise the Kryo object itself when suspending a fiber. That's dumb, useless AND can cause crashes, so
        // we avoid it here.
        register(Kryo::class.java, object : Serializer<Kryo>() {
            override fun write(kryo: Kryo, output: Output, obj: Kryo) {
            }

            override fun read(kryo: Kryo, input: Input, type: Class<Kryo>): Kryo {
                return createKryo((Fiber.getFiberSerializer() as KryoSerializer).kryo)
            }
        })

        // Some things where the JRE provides an efficient custom serialisation.
        val keyPair = generateKeyPair()
        register(keyPair.public.javaClass, Ed25519PublicKeySerializer)
        register(keyPair.private.javaClass, Ed25519PrivateKeySerializer)
        register(Instant::class.java, ReferencesAwareJavaSerializer)

        // Some classes have to be handled with the ImmutableClassSerializer because they need to have their
        // constructors be invoked (typically for lazy members).
        register(SignedTransaction::class.java, ImmutableClassSerializer(SignedTransaction::class))

        // This class has special handling.
        register(WireTransaction::class.java, WireTransactionSerializer)

        // This ensures a SerializedBytes<Foo> wrapper is written out as just a byte array.
        register(SerializedBytes::class.java, SerializedBytesSerializer)

        addDefaultSerializer(SerializeAsToken::class.java, SerializeAsTokenSerializer<SerializeAsToken>())

        // This is required to make all the unit tests pass
        register(Party::class.java)

        // This ensures a NonEmptySetSerializer is constructed with an initial value.
        register(NonEmptySet::class.java, NonEmptySetSerializer)

        /** This ensures any kotlin objects that implement [DeserializeAsKotlinObjectDef] are read back in as singletons. */
        addDefaultSerializer(DeserializeAsKotlinObjectDef::class.java, KotlinObjectSerializer)

        ImmutableListSerializer.registerSerializers(k)
        ImmutableSetSerializer.registerSerializers(k)
        ImmutableSortedSetSerializer.registerSerializers(k)
        ImmutableMapSerializer.registerSerializers(k)
        ImmutableMultimapSerializer.registerSerializers(k)

        noReferencesWithin<WireTransaction>()
    }
}

/**
 * Use this method to mark any types which can have the same instance within it more than once. This will make sure
 * the serialised form is stable across multiple serialise-deserialise cycles. Using this on a type with internal cyclic
 * references will throw a stack overflow exception during serialisation.
 */
inline fun <reified T : Any> Kryo.noReferencesWithin() {
    register(T::class.java, NoReferencesSerializer(getSerializer(T::class.java)))
}

class NoReferencesSerializer<T>(val baseSerializer: Serializer<T>) : Serializer<T>() {

    override fun read(kryo: Kryo, input: Input, type: Class<T>): T {
        val previousValue = kryo.setReferences(false)
        try {
            return baseSerializer.read(kryo, input, type)
        } finally {
            kryo.references = previousValue
        }
    }

    override fun write(kryo: Kryo, output: Output, obj: T) {
        val previousValue = kryo.setReferences(false)
        try {
            baseSerializer.write(kryo, output, obj)
        } finally {
            kryo.references = previousValue
        }
    }
}

/**
 * Improvement to the builtin JavaSerializer by honouring the [Kryo.getReferences] setting.
 */
object ReferencesAwareJavaSerializer : JavaSerializer() {
    override fun write(kryo: Kryo, output: Output, obj: Any) {
        if (kryo.references) {
            super.write(kryo, output, obj)
        } else {
            ObjectOutputStream(output).use {
                it.writeObject(obj)
            }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<Any>): Any {
        return if (kryo.references) {
            super.read(kryo, input, type)
        } else {
            ObjectInputStream(input).use(ObjectInputStream::readObject)
        }
    }
}

val ATTACHMENT_STORAGE = "ATTACHMENT_STORAGE"

var Kryo.attachmentStorage: AttachmentStorage?
    get() = this.context.get(ATTACHMENT_STORAGE, null) as AttachmentStorage?
    set(value) {
        this.context.put(ATTACHMENT_STORAGE, value)
    }


//TODO: It's a little workaround for serialization of HashMaps inside contract states.
//Used in Merkle tree calculation. It doesn't cover all the cases of unstable serialization format.
fun extendKryoHash(kryo: Kryo): Kryo {
    return kryo.apply {
        setReferences(false)
        register(LinkedHashMap::class.java, MapSerializer())
        register(HashMap::class.java, OrderedSerializer)
    }
}

object OrderedSerializer : Serializer<HashMap<Any, Any>>() {
    override fun write(kryo: Kryo, output: Output, obj: HashMap<Any, Any>) {
        //Change a HashMap to LinkedHashMap.
        val linkedMap = LinkedHashMap<Any, Any>()
        val sorted = obj.toList().sortedBy { it.first.hashCode() }
        for ((k, v) in sorted) {
            linkedMap.put(k, v)
        }
        kryo.writeClassAndObject(output, linkedMap)
    }

    //It will be deserialized as a LinkedHashMap.
    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<HashMap<Any, Any>>): HashMap<Any, Any> {
        val hm = kryo.readClassAndObject(input) as HashMap<Any, Any>
        return hm
    }
}
