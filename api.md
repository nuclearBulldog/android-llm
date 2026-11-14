LiteRT-LM provides APIs for multiple platforms, allowing you to
integrate large language models into your applications with ease.

## Installation \& Dependency Setup

Before writing code, you need to add the LiteRT-LM SDK to your project.
Use the following tabs to see how to configure the dependency for your
target language.

### Python

Install the official API package from PyPI:

    # Using pip
    pip install litert-lm-api

### Kotlin

Add the Maven dependency to your `build.gradle` file:

    dependencies {
        // For Android apps
        implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

        // For JVM-based desktop apps (Linux, macOS, Windows)
        implementation("com.google.ai.edge.litertlm:litertlm-jvm:latest.release")
    }

### Swift

Integrate LiteRT-LM natively in Xcode using **Swift Package Manager (SPM)**:

1. In Xcode, select **File \> Add Package Dependencies...**
2. Enter the repository URL: `https://github.com/google-ai-edge/LiteRT-LM`
3. Select **LiteRTLM** and add it to your target.

Or add it to your `Package.swift` dependencies:

    dependencies: [
      .package(url: "https://github.com/google-ai-edge/LiteRT-LM", from: "0.12.0")
    ]

### JavaScript

Install the package from npm,

    # Using npm
    npm install --save @litert-lm/core

or import it directly from a CDN in your HTML:

    <!-- Direct HTML CDN import -->
    <script type="module">
      import { Engine } from 'https://cdn.jsdelivr.net/npm/@litert-lm/core/+esm';
    </script>

*** ** * ** ***

## Quick Start Example

All official LiteRT-LM SDKs share a similar workflow:

1. **Initialize the Engine**: Load the model weights and configure execution settings (such as hardware acceleration).
2. **Create a Conversation**: Manage the history and state of a chat session.
3. **Send Messages**: Send prompts to the model and receive responses (either blocking or streaming).

The following is a comparison of how to perform a basic text-generation
request across different platforms using the provided SDKs.

### Python

    import litert_lm

    # 1. Initialize the Engine
    with litert_lm.Engine("path/to/model.litertlm") as engine:

      # 2. Create a Conversation
      with engine.create_conversation() as conversation:

        # 3. Send a message and get the response
        response = conversation.send_message("What is the capital of France?")
        print(response["content"][0]["text"])

### Kotlin

    import com.google.ai.edge.litertlm.*

    suspend fun main() {
      // 1. Initialize the Engine
      val config = EngineConfig(modelPath = "path/to/model.litertlm")
      Engine(config).use { engine ->
        engine.initialize()

        // 2. Create a Conversation
        engine.createConversation().use { conversation ->

          // 3. Send a message and get the response
          val response = conversation.sendMessage("What is the capital of France?")
          println(response)
        }
      }
    }

### Swift

    import LiteRTLM

    // 1. Initialize the Engine
    let config = try EngineConfig(
      modelPath: "path/to/model.litertlm",
      backend: .gpu,
      cacheDir: NSTemporaryDirectory()
    )
    let engine = Engine(engineConfig: config)
    try await engine.initialize()

    // 2. Create a Conversation
    let conversation = try await engine.createConversation()

    // 3. Send a message and get the response
    let response = try await conversation.sendMessage(Message("What is the capital of France?"))
    print(response.toString)

### JavaScript

    import { Engine } from '@litert-lm/core';

    // 1. Initialize the Engine
    const engine = await Engine.create({
      model: 'url/path/to/model.litertlm'
    });

    // 2. Create a Conversation
    const conversation = await engine.createConversation();

    // 3. Send a message and get the response
    const response = await conversation.sendMessage("What is the capital of France?");
    console.log(response.content[0].text);

    // Clean up
    await engine.delete();

*** ** * ** ***

## Hardware Acceleration (GPU)

You can run LiteRT-LM models on your device's GPU to accelerate inference
speeds. The following is how to configure the engine to run on the GPU.

### Python

    import litert_lm

    # Set backend to GPU
    with litert_lm.Engine("path/to/model.litertlm", backend=litert_lm.Backend.GPU()) as engine:
        with engine.create_conversation() as conversation:
            response = conversation.send_message("Hello!")
            print(response["content"][0]["text"])

### Kotlin

    import com.google.ai.edge.litertlm.*

    suspend fun main() {
      // Set backend to GPU in EngineConfig
      val config = EngineConfig(
          modelPath = "path/to/model.litertlm",
          backend = Backend.GPU()
      )
      Engine(config).use { engine ->
        engine.initialize()
        engine.createConversation().use { conversation ->
          val response = conversation.sendMessage("Hello!")
          println(response)
        }
      }
    }

### Swift

    import LiteRTLM

    // Set backend to .gpu in EngineConfig
    let config = try EngineConfig(
      modelPath: "path/to/model.litertlm",
      backend: .gpu(),
      cacheDir: NSTemporaryDirectory()
    )
    let engine = Engine(engineConfig: config)
    try await engine.initialize()

    let conversation = try await engine.createConversation()
    let response = try await conversation.sendMessage(Message("Hello!"))
    print(response.toString)

### JavaScript

WebGPU acceleration is enabled by default in the Web SDK and does not
require any manual backend configuration:

    import { Engine } from '@litert-lm/core';

    // WebGPU is used automatically
    const engine = await Engine.create({
      model: 'url/path/to/model.litertlm'
    });

    const conversation = await engine.createConversation();
    const response = await conversation.sendMessage("Hello!");
    console.log(response.content[0].text);

*** ** * ** ***

## Multi-Modality (Images)

LiteRT-LM supports multimodal input (such as image attachments) when using
compatible models.

### Python

    import litert_lm

    # Initialize engine with a vision backend
    with litert_lm.Engine(
        "path/to/multimodal_model.litertlm",
        vision_backend=litert_lm.Backend.GPU()
    ) as engine:
        with engine.create_conversation() as conversation:
            # Send image attachment alongside text prompt
            response = conversation.send_message(
                litert_lm.Contents.of(
                    "Describe this image.",
                    litert_lm.Content.ImageFile(absolute_path="path/to/image.jpg")
                )
            )
            print(response["content"][0]["text"])

### Kotlin

    import com.google.ai.edge.litertlm.*

    suspend fun main() {
      // Initialize engine with a vision backend
      val config = EngineConfig(
          modelPath = "path/to/multimodal_model.litertlm",
          visionBackend = Backend.GPU()
      )
      Engine(config).use { engine ->
        engine.initialize()
        engine.createConversation().use { conversation ->
          // Send image attachment alongside text prompt
          val response = conversation.sendMessage(Contents.of(
              Content.Text("Describe this image."),
              Content.ImageFile("path/to/image.jpg"),
          ))
          println(response)
        }
      }
    }

### Swift

    import LiteRTLM

    // Initialize engine with a vision backend
    let config = try EngineConfig(
      modelPath: "path/to/multimodal_model.litertlm",
      visionBackend: .cpu(), // Or .gpu() if supported
      cacheDir: NSTemporaryDirectory()
    )
    let engine = Engine(engineConfig: config)
    try await engine.initialize()

    let conversation = try await engine.createConversation()

    // Send image attachment alongside text prompt
    let message = Message(contents: [
      Content.text("Describe this image."),
      Content.imageFile("path/to/image.jpg")
    ])
    let response = try await conversation.sendMessage(message)
    print(response.toString)

### JavaScript

> [!NOTE]
> **Note:** The LiteRT-LM Web SDK only supports text-in / text-out running on WebGPU and does not support multimodal attachments (like images or audio) yet.

*** ** * ** ***

## Function Calling (Tool Use)

Function calling allows the language model to request the execution of
client-side code tools to perform actions or retrieve information.

### Python

    import litert_lm

    # 1. Define a tool function
    def get_current_time() -> str:
        """Returns the current time."""
        return "12:00 PM"

    # 2. Register the tool with the conversation
    tools = [get_current_time]
    with engine.create_conversation(tools=tools) as conversation:
        # The model will invoke the tool automatically if it needs it
        response = conversation.send_message("What time is it?")
        print(response["content"][0]["text"])

### Kotlin

    import com.google.ai.edge.litertlm.*

    // 1. Define a ToolSet
    class MyToolSet: ToolSet {
        @Tool(description = "Get the current time")
        fun getCurrentTime(): String {
            return "12:00 PM"
        }
    }

    suspend fun main() {
      // 2. Register the tool with ConversationConfig
      val config = ConversationConfig(
          tools = listOf(tool(MyToolSet()))
      )
      val conversation = engine.createConversation(config)

      // The model will invoke the tool automatically if it needs it
      val response = conversation.sendMessage("What time is it?")
      println(response)
    }

### Swift

    import LiteRTLM

    // 1. Define a Tool conforming to the Tool protocol
    struct GetCurrentTimeTool: Tool {
      static let name = "get_current_time"
      static let description = "Get the current time."

      func run() async throws -> Any {
        return ["time": "12:00 PM"]
      }
    }

    // 2. Register the tool in ConversationConfig
    let config = ConversationConfig(
      tools: [GetCurrentTimeTool()]
    )
    let conversation = try await engine.createConversation(with: config)

    // The model will invoke the tool automatically if it needs it
    let response = try await conversation.sendMessage(Message("What time is it?"))
    print(response.toString)

### JavaScript

    import { AutoToolChat } from '@litert-lm/core';

    // 1. Define a tool with an execute function and a JSON Schema
    const getCurrentTimeTool = {
      name: 'get_current_time',
      description: 'Get the current time.',
      parameters: { type: 'object', properties: {} },
      execute: () => ({ time: '12:00 PM' }),
    };

    // 2. Register the tool with AutoToolChat
    const chat = new AutoToolChat({
      engine,
      tools: [getCurrentTimeTool],
    });

    // The model will invoke the tool automatically if it needs it
    const response = await chat.sendMessage('What time is it?');
    console.log(response.content[0].text);

## Thinking

When running reasoning-capable models, LiteRT-LM supports generating
internal reasoning tokens ("thinking") before producing the final user-facing
response.

You can configure thinking generation using `ThinkingConfig`:

- **Enable / Disable Thinking**: Toggle whether reasoning tokens are generated.
- **Thinking Token Budget** : Limit the maximum number of reasoning tokens produced by the model. Setting `-1` allows unlimited thinking (the default), `0` disables thinking, and any positive integer (such as `1024`) enforces a strict token budget.

Reasoning tokens are delivered through a dedicated `thought` channel, keeping
internal thought processes separate from final answer text.

### Python

    import litert_lm

    # 1. Initialize the Engine
    with litert_lm.Engine("path/to/thinking_model.litertlm") as engine:

      # 2. Configure thinking and token budget for the conversation
      thinking_config = litert_lm.ThinkingConfig(
          enable_thinking=True,
          thinking_token_budget=1024,  # -1 for unlimited, 0 to disable
      )
      with engine.create_conversation(
          thinking_config=thinking_config,
          max_output_tokens=2048,
      ) as conversation:

        # 3. Send a message
        response = conversation.send_message("Solve this logic puzzle step by step.")

        # 4. Access reasoning tokens and final answer
        thought = response.get("channels", {}).get("thought")
        if thought:
          print("Thought:", thought)
        print("Answer:", response["content"][0]["text"])

        # Optional: Override thinking config per message
        quick_response = conversation.send_message(
            "Give a concise answer.",
            thinking_config=litert_lm.ThinkingConfig(thinking_token_budget=256),
        )

### Kotlin

    import com.google.ai.edge.litertlm.*

    suspend fun main() {
      // 1. Initialize the Engine
      val config = EngineConfig(modelPath = "path/to/thinking_model.litertlm")
      Engine(config).use { engine ->
        engine.initialize()

        // 2. Configure thinking and token budget for the conversation
        val conversationConfig = ConversationConfig(
            thinkingConfig = ThinkingConfig(
                enableThinking = true,
                thinkingTokenBudget = 1024 // -1 for unlimited, 0 to disable
            ),
            maxOutputToken = 2048
        )

        engine.createConversation(conversationConfig).use { conversation ->
          // 3. Send a message
          val response = conversation.sendMessage("Solve this logic puzzle step by step.")

          // 4. Access reasoning tokens and final answer
          val thought = response.channels["thought"]
          if (!thought.isNullOrEmpty()) {
            println("Thought: $thought")
          }
          println("Answer: $response")

          // Optional: Override thinking config per message
          val quickResponse = conversation.sendMessage(
              "Give a concise answer.",
              thinkingConfig = ThinkingConfig(thinkingTokenBudget = 256)
          )
        }
      }
    }

### Swift

    import LiteRTLM

    // 1. Initialize the Engine
    let config = try EngineConfig(
      modelPath: "path/to/thinking_model.litertlm",
      backend: .gpu,
      cacheDir: NSTemporaryDirectory()
    )
    let engine = Engine(engineConfig: config)
    try await engine.initialize()

    // 2. Configure thinking and token budget for the conversation
    let convConfig = ConversationConfig(
      thinkingConfig: ThinkingConfig(
        enableThinking: true,
        thinkingTokenBudget: 1024 // -1 for unlimited, 0 to disable
      )
    )
    let conversation = try await engine.createConversation(with: convConfig)

    // 3. Send a message
    let response = try await conversation.sendMessage(
      Message("Solve this logic puzzle step by step.")
    )

    // 4. Access reasoning tokens and final answer
    if let thought = response.channels["thought"] {
      print("Thought: \(thought)")
    }
    print("Answer: \(response.toString)")

    // Optional: Override thinking config per message
    let quickResponse = try await conversation.sendMessage(
      Message("Give a concise answer."),
      thinkingConfig: ThinkingConfig(thinkingTokenBudget: 256)
    )

### JavaScript

    import { Engine } from '@litert-lm/core';

    // 1. Initialize the Engine
    const engine = await Engine.create({
      model: 'url/path/to/thinking_model.litertlm'
    });

    // 2. Configure thinking using extra_context in the conversation preface
    const conversation = await engine.createConversation({
      preface: {
        extra_context: {
          enable_thinking: true, // Set to false to disable thinking
        }
      },
      sessionConfig: {
        maxOutputTokens: 2048,
      }
    });

    // 3. Send a message
    const response = await conversation.sendMessage("Solve this logic puzzle step by step.");

    // 4. Access reasoning tokens and final answer
    if (response.channels?.['thought']) {
      console.log("Thought:", response.channels['thought']);
    }
    console.log("Answer:", response.content?.[0]?.text ?? response.content);

    // Clean up
    await engine.delete();

> [!NOTE]
> **Note:** The LiteRT-LM Web SDK supports toggling thinking mode using `extra_context` in the conversation preface. Dedicated token budget constraints are supported in the Python, Kotlin, and Swift SDKs.

## Next Steps

Explore the detailed integration guides for your target platform:

- [Python SDK](https://developers.google.com/edge/litert-lm/python)
- [Kotlin (Android/JVM) SDK](https://developers.google.com/edge/litert-lm/android)
- [Swift (iOS/macOS) SDK](https://developers.google.com/edge/litert-lm/swift)
- [Web (JavaScript/TypeScript) SDK](https://developers.google.com/edge/litert-lm/js)
- [Cross-Platform C++ SDK](https://developers.google.com/edge/litert-lm/cpp)
- [Embedding Models](https://developers.google.com/edge/litert-lm/embedding_models)

For **Flutter** development, check out the community-maintained
[flutter_gemma](https://github.com/DenisovAV/flutter_gemma) package.

The Kotlin API of LiteRT-LM for **Android** and **JVM (Linux, macOS, Windows)**
with features like **GPU and NPU acceleration** , **multi-modality** , and
**tools use**.

## Introduction

Here is a sample terminal chat app built with the Kotlin API:

    import com.google.ai.edge.litertlm.*

    suspend fun main() {
      Engine.setNativeMinLogSeverity(LogSeverity.ERROR) // Hide log for TUI app

      val engineConfig = EngineConfig(modelPath = "/path/to/model.litertlm")
      Engine(engineConfig).use { engine ->
        engine.initialize()

        engine.createConversation().use { conversation ->
          while (true) {
            print("\n>>> ")
            conversation.sendMessageAsync(readln()).collect { print(it) }
          }
        }
      }
    }

![Demo for the Kotlin sample code](https://developers.google.com/static/edge/litert-lm/images/demo.gif)

To try out the above sample, clone the repository and run with
[example/Main.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/example/Main.kt):

    bazel run -c opt //kotlin/java/com/google/ai/edge/litertlm/example:main -- <abs_model_path>

Available `.litertlm` models are on the
[HuggingFace LiteRT Community](https://huggingface.co/litert-community). The
above animation was using the
[Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT).

For Android sample, check out the
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) app
([available on Google Play](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery)).

## Getting Started with Gradle

While LiteRT-LM is developed with Bazel, we provide the Maven packages for
Gradle or Maven users.

### Add the Gradle dependency

    dependencies {
        // For Android
        implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

        // For JVM (Linux, macOS, Windows)
        implementation("com.google.ai.edge.litertlm:litertlm-jvm:latest.release")
    }

You can find the available versions on Google Maven in
[litertlm-android](https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android)
and
[litertlm-jvm](https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-jvm).

`latest.release` can be used to get the latest release.

### Initialize the Engine

The `Engine` is the entry point to the API. Initialize it with the model path
and configuration. Remember to close the engine to release resources.

**Note:** The `engine.initialize()` method can take a significant amount of time
(e.g., up to 10 seconds) to load the model. It is strongly recommended to call
this on a background thread or coroutine to avoid blocking the UI thread.

    import com.google.ai.edge.litertlm.Backend
    import com.google.ai.edge.litertlm.Engine
    import com.google.ai.edge.litertlm.EngineConfig

    val engineConfig = EngineConfig(
        modelPath = "/path/to/your/model.litertlm", // Replace with your model path
        backend = Backend.GPU(), // Or Backend.NPU(nativeLibraryDir = "...")
        // Optional: Pick a writable dir. This can improve 2nd load time.
        // cacheDir = "/tmp/" or context.cacheDir.path (for Android)
    )

    val engine = Engine(engineConfig)
    engine.initialize()
    // ... Use the engine to create a conversation ...

    // Close the engine when done
    engine.close()

On Android, to use the GPU backend, the app needs to request the depending
native libraries explicitly by adding the following to your
`AndroidManifest.xml` inside the `<application>` tag:

      <application>
        <uses-native-library android:name="libvndksupport.so" android:required="false"/>
        <uses-native-library android:name="libOpenCL.so" android:required="false"/>
      </application>

To use the **NPU** backend, you might need to specify the directory containing
the NPU libraries. On Android, if the libraries are bundled with your app, set
it to `context.applicationInfo.nativeLibraryDir`. See [LiteRT-LM
NPU](https://ai.google.dev/edge/litert/next/litert_lm_npu#NPU) for more details
about the NPU native libraries.

    val engineConfig = EngineConfig(
        modelPath = modelPath,
        backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    )

### Create a Conversation

Once the engine is initialized, create a `Conversation` instance. You can
provide a `ConversationConfig` to customize its behavior.

    import com.google.ai.edge.litertlm.ConversationConfig
    import com.google.ai.edge.litertlm.Message
    import com.google.ai.edge.litertlm.SamplerConfig

    // Optional: Configure the system instruction, initial messages, sampling
    // parameters, etc.
    val conversationConfig = ConversationConfig(
        systemInstruction = Contents.of("You are a helpful assistant."),
        initialMessages = listOf(
            Message.user("What is the capital city of the United States?"),
            Message.model("Washington, D.C."),
        ),
        samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.8),
    )

    val conversation = engine.createConversation(conversationConfig)
    // Or with default config:
    // val conversation = engine.createConversation()

    // ... Use the conversation ...

    // Close the conversation when done
    conversation.close()

`Conversation` implements `AutoCloseable`, so you can use the `use` block for
automatic resource management for one-shot or short-lived conversations:

    engine.createConversation(conversationConfig).use { conversation ->
        // Interact with the conversation
    }

### Sending Messages

There are three ways to send messages:

- **`sendMessage(contents): Message`**: Synchronous call that blocks until the model returns a complete response. This is simpler for basic request and response interactions.
- **`sendMessageAsync(contents, callback)`**: Asynchronous call for streaming responses. This is better for long-running requests or when you want to display the response as it's being generated.
- **`sendMessageAsync(contents): Flow<Message>`**: Asynchronous call that returns a Kotlin Flow for streaming responses. This is the recommended approach for Coroutine users.

**Synchronous Example:**

    import com.google.ai.edge.litertlm.Content
    import com.google.ai.edge.litertlm.Message

    print(conversation.sendMessage("What is the capital of France?"))

**Asynchronous Example with callback:**

Use `sendMessageAsync` to send a message to the model and receive responses
through a callback.

    import com.google.ai.edge.litertlm.Content
    import com.google.ai.edge.litertlm.Message
    import com.google.ai.edge.litertlm.MessageCallback
    import java.util.concurrent.CountDownLatch
    import java.util.concurrent.TimeUnit

    val callback = object : MessageCallback {
        override fun onMessage(message: Message) {
            print(message)
        }

        override fun onDone() {
            // Streaming completed
        }

        override fun onError(throwable: Throwable) {
            // Error during streaming
        }
    }

    conversation.sendMessageAsync("What is the capital of France?", callback)

**Asynchronous Example with Flow:**

Use `sendMessageAsync` (without the callback arg) to send a message to the model
and receive responses through a Kotlin Flow.

    import com.google.ai.edge.litertlm.Content
    import com.google.ai.edge.litertlm.Message
    import kotlinx.coroutines.flow.catch
    import kotlinx.coroutines.launch

    // Within a coroutine scope
    conversation.sendMessageAsync("What is the capital of France?")
        .catch { ... } // Error during streaming
        .collect { print(it.toString()) }

### Multi-Token Prediction (MTP)

Multi-Token Prediction (MTP) is a performance optimization that significantly
accelerates decode speeds. MTP is universally recommended for all tasks on GPU
backends.

To use MTP, enable speculative decoding using `ExperimentalFlags` before
initializing the engine.

    import com.google.ai.edge.litertlm.ExperimentalApi
    import com.google.ai.edge.litertlm.ExperimentalFlags
    import com.google.ai.edge.litertlm.Backend
    import com.google.ai.edge.litertlm.Engine
    import com.google.ai.edge.litertlm.EngineConfig

    // Enable MTP via speculative decoding
    @OptIn(ExperimentalApi::class)
    ExperimentalFlags.enableSpeculativeDecoding = true

    val engineConfig = EngineConfig(
        modelPath = "/path/to/your/model.litertlm",
        backend = Backend.GPU(),
    )

    val engine = Engine(engineConfig)
    engine.initialize()

    // The same steps to create Conversation and send messages as below...

### Multi-Modality

> [!NOTE]
> **Note:** This only works with models with multi-modality support, e.g., the [Gemma3n](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm).

`Message` objects can contain different types of `Content`, including `Text`,
`ImageBytes`, `ImageFile`, and `AudioBytes`, `AudioFile`.

    // Initialize the `visionBackend`, `audioBackend`, or both
    val engineConfig = EngineConfig(
        modelPath = "/path/to/your/model.litertlm", // Replace with your model path
        backend = Backend.CPU(), // Or Backend.GPU() or Backend.NPU(...)
        visionBackend = Backend.GPU(), // Or Backend.NPU(...)
        audioBackend = Backend.CPU(), // Or Backend.NPU(...)
    )

    // Sends a message with multi-modality.
    // See the Content class for other variants.
    conversation.sendMessage(Contents.of(
        Content.ImageFile("/path/to/image"),
        Content.AudioBytes(audioBytes), // ByteArray of the audio
        Content.Text("Describe this image and audio."),
    ))

### Defining and Using Tools

> [!NOTE]
> **Note:** This only works with models with tool support, e.g., the [FunctionGemma](https://huggingface.co/google/functiongemma-270m-it).

There are two ways to define tools:

1. With Kotlin functions (recommended for most cases)
2. With Open API specification (full control of the tool spec and execution)

#### Defining Tools with Kotlin Functions

You can define custom Kotlin functions as tools that the model can call to
perform actions or fetch information.

Create a class implementing `ToolSet` and annotate methods with `@Tool` and
parameters with `@ToolParam`.

    import com.google.ai.edge.litertlm.Tool
    import com.google.ai.edge.litertlm.ToolParam

    class SampleToolSet: ToolSet {
        @Tool(description = "Get the current weather for a city")
        fun getCurrentWeather(
            @ToolParam(description = "The city name, e.g., San Francisco") city: String,
            @ToolParam(description = "Optional country code, e.g., US") country: String? = null,
            @ToolParam(description = "Temperature unit (celsius or fahrenheit). Default: celsius") unit: String = "celsius"
        ): Map<String, Any> {
            // In a real application, you would call a weather API here
            return mapOf("temperature" to 25, "unit" to  unit, "condition" to "Sunny")
        }

        @Tool(description = "Get the sum of a list of numbers.")
        fun sum(
            @ToolParam(description = "The numbers, could be floating point.") numbers: List<Double>,
        ): Double {
            return numbers.sum()
        }
    }

Behind the scenes, the API inspects these annotations and the function signature
to generate an OpenAPI-style schema. This schema describes the tool's
functionality, parameters (including their types and descriptions from
`@ToolParam`), and return type to the language model.

##### Parameter Types

The types for parameters annotated with `@ToolParam` can be `String`, `Int`,
`Boolean`, `Float`, `Double`, or a `List` of these types (e.g., `List<String>`).
Use nullable types (e.g., `String?`) to indicate nullable parameters. Set a
default value to indicate that the parameter is optional, and mention the
default value in the description in `@ToolParam`.

##### Return Type

The return type of your tool function can be any Kotlin type. The result will be
converted to a JSON element before being sent back to the model.

- `List` types are converted to JSON arrays.
- `Map` types are converted to JSON objects.
- Primitive types (`String`, `Number`, `Boolean`) are converted to the corresponding JSON primitive.
- Other types are converted to strings with the `toString()` method.

For structured data, returning `Map` or a data class that will be converted to a
JSON object is recommended.

#### Defining Tools with OpenAPI Specification

Alternatively, you can define a tool by implementing the `OpenApiTool` class and
providing the tool's description as a JSON string conforming to the Open API
specification. This method is useful if you already have an OpenAPI schema for
your tool or if you need fine-grained control over the tool's definition.

    import com.google.ai.edge.litertlm.OpenApiTool

    class SampleOpenApiTool : OpenApiTool {

        override fun getToolDescriptionJsonString(): String {
            return """
            {
              "name": "addition",
              "description": "Add all numbers.",
              "parameters": {
                "type": "object",
                "properties": {
                  "numbers": {
                    "type": "array",
                    "items": {
                      "type": "number"
                    }
                  },
                  "description": "The list of numbers to sum."
                },
                "required": [
                  "numbers"
                ]
              }
            }
            """.trimIndent() // Tip: trim to save tokens
        }

        override fun execute(paramsJsonString: String): String {
            // Parse paramsJsonString with your choice of parser or deserializer and
            // execute the tool.

            // Return the result as a JSON string
            return """{"result": 1.4142}"""
        }
    }

#### Registering Tools

Include instances of your tools in the `ConversationConfig`.

    val conversation = engine.createConversation(
        ConversationConfig(
            tools = listOf(
                tool(SampleToolSet()),
                tool(SampleOpenApiTool()),
            ),
            // ... other configs
        )
    )

    // Send messages that might trigger the tool
    conversation.sendMessageAsync("What's the weather like in London?", callback)

The model will decide when to call the tool based on the conversation. The
results from the tool execution are automatically sent back to the model to
generate the final response.

#### Manual Tool Calling

By default, tool calls generated by the model are automatically executed by
LiteRT-LM and the results from the tool execution are automatically sent back to
the model to generate the next response.

If you want to manually execute tools and send results back to the model, you
can set `automaticToolCalling` in `ConversationConfig` to `false`.

    val conversation = engine.createConversation(
        ConversationConfig(
            tools = listOf(
                tool(SampleOpenApiTool()),
            ),
            automaticToolCalling = false,
        )
    )

If you disable automatic tool calling, you will need to manually execute tools
and send results back to the model in your application code. The `execute`
method of `OpenApiTool` will **not** be called automatically when
`automaticToolCalling` is set to `false`.

    // Send a message that triggers a tool call.
    val responseMessage = conversation.sendMessage("What's the weather like in London?")

    // The model returns a Message with `toolCalls` populated.
    if (responseMessage.toolCalls.isNotEmpty()) {
        val toolResponses = mutableListOf<Content.ToolResponse>()
        // There can be multiple tool calls in a single response.
        for (toolCall in responseMessage.toolCalls) {
            println("Model wants to call: ${toolCall.name} with arguments: ${toolCall.arguments}")

            // Execute the tool manually with your own logic. `executeTool` is just an example here.
            val toolResponseJson = executeTool(toolCall.name, toolCall.arguments)

            // Collect tool responses.
            toolResponses.add(Content.ToolResponse(toolCall.name, toolResponseJson))
        }

        // Use Message.tool to create the tool response message.
        val toolResponseMessage = Message.tool(Contents.of(toolResponses))

        // Send the tool response message to the model.
        val finalMessage = conversation.sendMessage(toolResponseMessage)
        println("Final answer: ${finalMessage.text}") // e.g., "The weather in London is 25c."
    }

#### Example

To try out tool use, clone the repository and run with
[example/ToolMain.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/example/ToolMain.kt):

    bazel run -c opt //kotlin/java/com/google/ai/edge/litertlm/example:tool -- <abs_model_path>

### Thinking and Reasoning

When running reasoning-capable models, LiteRT-LM supports generating
internal reasoning tokens ("thinking") before producing the final response.

You can configure thinking generation using `ThinkingConfig`:

- **`enableThinking`** : Toggle whether reasoning tokens are generated (`true` or `false`).
- **`thinkingTokenBudget`** : Limit the maximum number of reasoning tokens produced by the model. Setting `-1` allows unlimited thinking (the default), `0` disables thinking, and any positive integer (such as `1024`) enforces a strict token budget.

Reasoning tokens are delivered through a dedicated `thought` channel in the
response, keeping internal thought processes separate from the final answer
text.

    import com.google.ai.edge.litertlm.*

    suspend fun main() {
      // 1. Initialize the Engine
      val config = EngineConfig(modelPath = "path/to/thinking_model.litertlm")
      Engine(config).use { engine ->
        engine.initialize()

        // 2. Configure thinking and token budget for the conversation
        val conversationConfig = ConversationConfig(
            thinkingConfig = ThinkingConfig(
                enableThinking = true,
                thinkingTokenBudget = 1024 // -1 for unlimited, 0 to disable
            ),
            maxOutputToken = 2048
        )

        engine.createConversation(conversationConfig).use { conversation ->
          // 3. Send a message
          val response = conversation.sendMessage("Solve this logic puzzle step by step.")

          // 4. Access reasoning tokens and final answer
          val thought = response.channels["thought"]
          if (!thought.isNullOrEmpty()) {
            println("Thought: $thought")
          }
          println("Answer: $response")

          // Optional: Override thinking config per message
          val quickResponse = conversation.sendMessage(
              "Give a concise answer.",
              thinkingConfig = ThinkingConfig(thinkingTokenBudget = 256)
          )
        }
      }
    }

## Error Handling

API methods can throw `LiteRtLmJniException` for errors from the native layer or
standard Kotlin exceptions like `IllegalStateException` for lifecycle issues.
Always wrap API calls in try-catch blocks. The `onError` callback in
`MessageCallback` will also report errors during asynchronous operations.

Gemma 4 models are designed to deliver frontier-level performance at each size,
targeting deployment scenarios from mobile and edge devices (E2B, E4B) to
consumer GPUs and workstations (26B A4B, 31B). They are well-suited for
reasoning, agentic workflows, coding, and multimodal understanding.

> [!NOTE]
> **Note:** LiteRT-LM supports E2B and E4B models today, with support for larger models coming soon.

Gemma 4 is licensed under the Apache-2.0 license. For more details, see the
[Gemma 4 Model Card](https://ai.google.dev/gemma/docs/core/model_card_4).

## Multi-Token Prediction

Multi-Token Prediction (MTP) is a new performance optimization that
significantly accelerates decode speeds across CPU and GPU backends with zero
quality degradation.

- **Performance Gains:**
  - **GPU:** Massive acceleration, delivering up to 2.2x decode speedup on mobile GPUs.
  - **CPU:** Performance boosts up to 1.5x speedup on mobile CPUs and significant acceleration on SME-enabled hardware (e.g., M4 MacBooks).
- **Recommendations:** MTP is universally recommended for all tasks on GPU backends and for the Gemma4-E4B model on CPU. For the Gemma4-E2B model on CPU, it is highly valuable for rewrite, summarize, and coding tasks, but should be enabled selectively as it may cause a slight slowdown during freeform prompting or generative tasks.

To try it out, see the platform-specific guides:

- [LiteRT-LM CLI](https://developers.google.com/edge/litert-lm/cli#mtp)
- [Python API Guide](https://developers.google.com/edge/litert-lm/python#mtp)
- [Android (Kotlin) Guide](https://developers.google.com/edge/litert-lm/android#mtp)
- [iOS (Swift) Guide](https://developers.google.com/edge/litert-lm/swift#mtp)
- [C++ API Guide](https://developers.google.com/edge/litert-lm/cpp#mtp)

## Get Started

Chat with Gemma4-E2B, hosted on the Hugging Face LiteRT Community.

    uv tool install litert-lm

    litert-lm run  \
      --from-huggingface-repo=litert-community/gemma-4-E2B-it-litert-lm \
      gemma-4-E2B-it.litertlm \
      --prompt="What is the capital of France?"

## Deploy from Safetensors

Follow these steps to deploy Gemma 4 starting from your custom safetensors (for
example, after fine-tuning the model for your use-case):

- Convert to a `.litertlm` format:

      uv tool install litert-torch-nightly

      litert-torch export_hf \
        --model=google/gemma-4-E2B-it \
        --output_dir=/tmp/gemma4_2b \
        --externalize_embedder \
        --jinja_chat_template_override=litert-community/gemma-4-E2B-it-litert-lm

- Deploy using
  [LiteRT-LM cross-platform APIs](https://developers.google.com/edge/litert-lm/overview#start_building):

      litert-lm run  \
        /tmp/gemma4_2b/model.litertlm \
        --prompt="What is the capital of France?"

## Performance Summary

### Gemma-4-E2B

- Model Size: 2.58 GB
- Additional technical details are in the
  [HuggingFace model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)

  | Platform (Device) | Backend | Prefill (tk/s) | Decode (tk/s) | Time to First Token (seconds) | Peak CPU Memory (MB) |
  |---|---|---|---|---|---|
  | Android (S26 Ultra) | CPU | 557 | 47 | 1.8 | 1733 |
  | Android (S26 Ultra) | GPU | 3808 | 52 | 0.3 | 676 |
  | iOS (iPhone 17 Pro) | CPU | 532 | 25 | 1.9 | 607 |
  | iOS (iPhone 17 Pro) | GPU | 2878 | 56 | 0.3 | 1450 |
  | Linux (Arm 2.3 \& 2.8 GHz, NVIDIA GeForce RTX 4090) | CPU | 260 | 35 | 4 | 1628 |
  | Linux (Arm 2.3 \& 2.8 GHz, NVIDIA GeForce RTX 4090) | GPU | 11234 | 143 | 0.1 | 913 |
  | macOS (MacBook Pro M4) | CPU | 901 | 42 | 1.1 | 736 |
  | macOS (MacBook Pro M4) | GPU | 7835 | 160 | 0.1 | 1623 |
  | Windows (Intel LunarLake) | CPU | 435 | 30 | 2.4 | 3505 |
  | Windows (Intel LunarLake) | GPU | 3751 | 48 | 0.3 | 3540 |
  | IoT (Raspberry Pi 5 16GB) | CPU | 133 | 8 | 7.8 | 1546 |

### Gemma-4-E4B

- Model Size: 3.65 GB
- Additional technical details are in the
  [HuggingFace model card](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)

  | Platform (Device) | Backend | Prefill (tk/s) | Decode (tk/s) | Time to First Token (seconds) | Peak CPU Memory (MB) |
  |---|---|---|---|---|---|
  | Android (S26 Ultra) | CPU | 195 | 18 | 5.3 | 3283 |
  | Android (S26 Ultra) | GPU | 1293 | 22 | 0.8 | 710 |
  | iOS (iPhone 17 Pro) | CPU | 159 | 10 | 6.5 | 961 |
  | iOS (iPhone 17 Pro) | GPU | 1189 | 25 | 0.9 | 3380 |
  | Linux (Arm 2.3 \& 2.8GHz / RTX 4090) | CPU | 82 | 18 | 12.6 | 3139 |
  | Linux (Arm 2.3 \& 2.8GHz / RTX 4090) | GPU | 7260 | 91 | 0.2 | 1119 |
  | macOS (MacBook Pro M4 Max) | CPU | 277 | 27 | 3.7 | 890 |
  | macOS (MacBook Pro M4 Max) | GPU | 2560 | 101 | 0.4 | 3217 |
  | Windows (Intel LunarLake) | CPU | 173 | 17 | 6.0 | 9372 |
  | Windows (Intel LunarLake) | GPU | 1202 | 25 | 0.9 | 7147 |
  | IoT (Raspberry Pi 5 16GB) | CPU | 51 | 3 | 20.5 | 3069 |
  
  This tutorial walks you through the end-to-end process of deploying a
customized Gemma model in an Android sample app.

In this tutorial, we will:

1. Start with a fine tuned model
2. Convert the fine tuned model with the AI Edge Torch library
   1. This will convert the HuggingFace checkpoint to a .litertlm file
3. Evaluate the quality of the fine tuned model
4. Run the adb commands to push the custom model to a test device
   1. This will push the model in .litertlm format to an Android Device for local testing
5. Run the [Pirate Gemma sample app](https://github.com/erintwalsh/on-device-gemma-tutorial)
   1. This will use the LiteRT-LM Kotlin library to run inference on the fine tuned model in a sample Android app

## Step 1: Start with a fine tuned model

For this tutorial, we will start with a fine tuned model. In order to show a
clear difference between a base model and a fine tuned one, we took a base
[Gemma270m model](https://huggingface.co/google/gemma-3-270m) and fine tuned it to speak like a pirate.

You can find the pre-fine tuned Pirate Gemma model at
`https://huggingface.co/erintwalsh/pirate-gemma`

For more information on fine tuning a model, see the [Gemma Cookbook](https://github.com/google-gemma/cookbook)
and [Unsloth](https://unsloth.ai/docs/models/gemma-4/train) guides.

## Step 2: Convert and run your custom model in the command line

Now that you have a custom model published on HuggingFace,
you can use the [AI Edge Torch library](https://github.com/google-ai-edge/litert-torch) to download and convert that
model to a `.litertlm` format.

### Prerequisites

Be sure to have [uv tool](https://docs.astral.sh/uv/) and Python 3.11 or later installed on your device.

### Convert model

    uv tool install litert-torch-nightly

    litert-torch export_hf \
      --model=[YOUR-HF-USERNAME]/pirate-gemma \
      --output_dir=/tmp/pirate-gemma \
      --externalize_embedder

### Run your model locally

    uv tool install litert-lm

    litert-lm run  \
      /tmp/pirate-gemma/model.litertlm \
      --prompt="Where is the nearest buried treasure?"

## Step 3: Evaluate model quality

### Assess quality of fine tuning

Once you have your model running locally, you can use the [AI Edge evaluation](https://github.com/google-ai-edge/eval/blob/main/README.md)
CLI tool to analyze the LiteRT-LM model quality of the fine tuning. This
tool offers built-in post-conversion quality parity validation between the
original HF model and the converted LiteRT-LM model with public datasets.
Beyond public datasets, you can create customized evaluation tasks using
your own data and specific metrics. See a [custom task example](https://github.com/google-ai-edge/eval/blob/main/examples/model_eval/pirate_gemma_custom_task.py) for
evaluating this fine tuned model using LLM-as-a-Judge.

    uv tool install ai-edge-eval

    GEMINI_API_KEY="your_actual_api_key_here"
    ai_edge_eval \
      --framework custom \
      --runner litert-lm \
      --model-path /path/to/your/model.litertlm \
      --custom-tasks-file pirate_gemma_custom_task.py \
      --tasks pirate_gemma_eval \
      --output-dir /tmp/results

## Step 4: Push your local model to your test device

Use these adb instructions to push the model you just ran onto your
physical Android device.

    adb shell rm -r /data/local/tmp/llm/ # Remove any previously loaded models

    adb shell mkdir -p /data/local/tmp/llm/

    adb push /tmp/pirate-gemma/model.litertlm  /data/local/tmp/llm/pirate-gemma.litertlm

## Step 5: Run inference on your custom model with the Captain Gemma sample app

[Captain Gemma](https://github.com/erintwalsh/on-device-gemma-tutorial) is an app that demonstrates interfacing with the
model on device. Find it on GitHub and clone the repository to
run it in Android Studio.

Open up the Captain Gemma sample app and run it on your
device. This is an app that uses Android Compose elements to
demonstrate running inference on a fine tuned model on your Android
device. The app takes text input from a user, and returns the fine
tuned Gemma model's pirate wisdom.

Run the app on your test device,
that you just pushed the `.litertlm` file to. Enter in a
query, and see the model's output in the white speech box.

![Main screen of the sample app](https://developers.google.com/static/edge/litert-lm/images/convert-and-run/sample_app_ss.png)

### Sample code

See examples of [setting up your model](https://github.com/erintwalsh/on-device-gemma-tutorial/blob/main/app/src/main/java/com/example/gemmathepirate/SessionViewModel.kt#L39) and [running inference](https://github.com/erintwalsh/on-device-gemma-tutorial/blob/main/app/src/main/java/com/example/gemmathepirate/SessionViewModel.kt#L55) with
the LiteRT-LM APIs in the Captain Gemma sample code.

## Step 6: Model deployment

Once you have finished locally testing your custom model and are ready
to deploy, there are options for hosting your model, such as:

- You can push your `.litertlm` file to the HuggingFace model repo you created earlier, and download it in your app using the [HuggingFace APIs](https://huggingface.co/docs/huggingface_hub/guides/download)
- You can use a hosting service such as Firebase, which provides APIs for your app to fetch your model URL and download for [Android](https://firebase.google.com/docs/storage/android/download-files) or [iOS.](https://firebase.google.com/docs/storage/ios/download-files)