package org.vulkanb.eng.graph;

import org.lwjgl.system.MemoryStack;
import org.tinylog.Logger;
import org.vulkanb.eng.*;
import org.vulkanb.eng.graph.animation.AnimationComputeActivity;
import org.vulkanb.eng.graph.geometry.GeometryRenderActivity;
import org.vulkanb.eng.graph.gui.GuiRenderActivity;
import org.vulkanb.eng.graph.lighting.LightingRenderActivity;
import org.vulkanb.eng.graph.shadows.ShadowRenderActivity;
import org.vulkanb.eng.graph.vk.Queue;
import org.vulkanb.eng.graph.vk.*;
import org.vulkanb.eng.scene.*;

import java.util.*;

import static org.lwjgl.vulkan.VK11.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;

public class Render {

    private final AnimationComputeActivity animationComputeActivity;
    private final CommandPool commandPool;
    private final Device device;
    private final GeometryRenderActivity geometryRenderActivity;
    private final GlobalBuffers globalBuffers;
    private final Queue.GraphicsQueue graphQueue;
    private final GuiRenderActivity guiRenderActivity;
    private final Instance instance;
    private final LightingRenderActivity lightingRenderActivity;
    private final PhysicalDevice physicalDevice;
    private final PipelineCache pipelineCache;
    private final Queue.PresentQueue presentQueue;
    private final ShadowRenderActivity shadowRenderActivity;
    private final Surface surface;
    private final TextureCache textureCache;
    private final List<VulkanModel> vulkanModels;
    private CommandBuffer[] commandBuffers;
    private long entitiesLoadedTimeStamp;
    private Fence[] fences;
    private SwapChain swapChain;

    public Render(Window window, Scene scene) {
        EngineProperties engProps = EngineProperties.getInstance();
        instance = new Instance(engProps.isValidate());
        physicalDevice = PhysicalDevice.createPhysicalDevice(instance, engProps.getPhysDeviceName());
        device = new Device(instance, physicalDevice);
        surface = new Surface(physicalDevice, window.getWindowHandle());
        graphQueue = new Queue.GraphicsQueue(device, 0);
        presentQueue = new Queue.PresentQueue(device, surface, 0);
        swapChain = new SwapChain(device, surface, window, engProps.getRequestedImages(), engProps.isvSync(),
                presentQueue, new Queue[]{graphQueue});
        commandPool = new CommandPool(device, graphQueue.getQueueFamilyIndex());
        pipelineCache = new PipelineCache(device);
        vulkanModels = new ArrayList<>();
        textureCache = new TextureCache();
        globalBuffers = new GlobalBuffers(device);
        geometryRenderActivity = new GeometryRenderActivity(swapChain, pipelineCache, scene, globalBuffers);
        shadowRenderActivity = new ShadowRenderActivity(swapChain, pipelineCache, scene, globalBuffers);
        List<Attachment> attachments = new ArrayList<>(geometryRenderActivity.getAttachments());
        attachments.add(shadowRenderActivity.getDepthAttachment());
        lightingRenderActivity = new LightingRenderActivity(swapChain, commandPool, pipelineCache, attachments, scene);
        animationComputeActivity = new AnimationComputeActivity(commandPool, pipelineCache);
        guiRenderActivity = new GuiRenderActivity(swapChain, commandPool, graphQueue, pipelineCache,
                lightingRenderActivity.getLightingFrameBuffer().getLightingRenderPass().getVkRenderPass());
        entitiesLoadedTimeStamp = 0;
        createCommandBuffers();
    }

    private CommandBuffer acquireCurrentCommandBuffer() {
        int idx = swapChain.getCurrentFrame();

        Fence fence = fences[idx];
        CommandBuffer commandBuffer = commandBuffers[idx];

        fence.fenceWait();
        fence.reset();

        return commandBuffer;
    }

    public void cleanup() {
        presentQueue.waitIdle();
        graphQueue.waitIdle();
        device.waitIdle();
        textureCache.cleanup();
        pipelineCache.cleanup();
        guiRenderActivity.cleanup();
        lightingRenderActivity.cleanup();
        animationComputeActivity.cleanup();
        shadowRenderActivity.cleanup();
        geometryRenderActivity.cleanup();
        Arrays.asList(commandBuffers).forEach(CommandBuffer::cleanup);
        Arrays.asList(fences).forEach(Fence::cleanup);
        commandPool.cleanup();
        swapChain.cleanup();
        surface.cleanup();
        globalBuffers.cleanup();
        device.cleanup();
        physicalDevice.cleanup();
        instance.cleanup();
    }

    private void createCommandBuffers() {
        int numImages = swapChain.getNumImages();
        commandBuffers = new CommandBuffer[numImages];
        fences = new Fence[numImages];

        for (int i = 0; i < numImages; i++) {
            commandBuffers[i] = new CommandBuffer(commandPool, true, false);
            fences[i] = new Fence(device, true);
        }
    }

    public void loadModels(List<ModelData> modelDataList) {
        Logger.debug("Loading {} model(s)", modelDataList.size());
        vulkanModels.addAll(globalBuffers.loadModels(modelDataList, textureCache, commandPool, graphQueue));
        Logger.debug("Loaded {} model(s)", modelDataList.size());

        geometryRenderActivity.loadModels(textureCache);
        shadowRenderActivity.loadModels(textureCache);
    }

    private void recordCommands() {
        int idx = 0;
        for (CommandBuffer commandBuffer : commandBuffers) {
            commandBuffer.reset();
            commandBuffer.beginRecording();
            geometryRenderActivity.recordCommandBuffer(commandBuffer, globalBuffers, idx);
            shadowRenderActivity.recordCommandBuffer(commandBuffer, globalBuffers, idx);
            commandBuffer.endRecording();
            idx++;
        }
    }

    public void render(Window window, Scene scene) {
        if (entitiesLoadedTimeStamp < scene.getEntitiesLoadedTimeStamp()) {
            entitiesLoadedTimeStamp = scene.getEntitiesLoadedTimeStamp();
            device.waitIdle();
            globalBuffers.loadEntities(vulkanModels, scene, commandPool, graphQueue, swapChain.getNumImages());
            animationComputeActivity.onAnimatedEntitiesLoaded(globalBuffers);
            recordCommands();
        }
        if (window.getWidth() <= 0 && window.getHeight() <= 0) {
            return;
        }
        int imageIndex;
        if (window.isResized() || (imageIndex = swapChain.acquireNextImage()) < 0) {
            window.resetResized();
            resize(window);
            scene.getProjection().resize(window.getWidth(), window.getHeight());
            imageIndex = swapChain.acquireNextImage();
        }

        globalBuffers.loadInstanceData(scene, vulkanModels, swapChain.getCurrentFrame());

        if (globalBuffers.getAnimVerticesBuffer() != null) {
            animationComputeActivity.recordCommandBuffer(globalBuffers);
            animationComputeActivity.submit();
        }

        CommandBuffer commandBuffer = acquireCurrentCommandBuffer();
        geometryRenderActivity.render();
        shadowRenderActivity.render();
        submitSceneCommand(graphQueue, commandBuffer);

        commandBuffer = lightingRenderActivity.beginRecording(shadowRenderActivity.getShadowCascades());
        lightingRenderActivity.recordCommandBuffer(commandBuffer);
        guiRenderActivity.recordCommandBuffer(scene, commandBuffer);
        lightingRenderActivity.endRecording(commandBuffer);
        lightingRenderActivity.submit(graphQueue);

        if (swapChain.presentImage(presentQueue, imageIndex)) {
            window.setResized(true);
        }
    }

    private void resize(Window window) {
        EngineProperties engProps = EngineProperties.getInstance();

        device.waitIdle();
        graphQueue.waitIdle();

        swapChain.cleanup();

        swapChain = new SwapChain(device, surface, window, engProps.getRequestedImages(), engProps.isvSync(),
                presentQueue, new Queue[]{graphQueue});
        geometryRenderActivity.resize(swapChain);
        shadowRenderActivity.resize(swapChain);
        recordCommands();
        List<Attachment> attachments = new ArrayList<>(geometryRenderActivity.getAttachments());
        attachments.add(shadowRenderActivity.getDepthAttachment());
        lightingRenderActivity.resize(swapChain, attachments);
        guiRenderActivity.resize(swapChain);
    }

    public void submitSceneCommand(Queue queue, CommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int idx = swapChain.getCurrentFrame();
            Fence currentFence = fences[idx];
            SwapChain.SyncSemaphores syncSemaphores = swapChain.getSyncSemaphoresList()[idx];
            queue.submit(stack.pointers(commandBuffer.getVkCommandBuffer()),
                    stack.longs(syncSemaphores.imgAcquisitionSemaphore().getVkSemaphore()),
                    stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT),
                    stack.longs(syncSemaphores.geometryCompleteSemaphore().getVkSemaphore()), currentFence);
        }
    }
}