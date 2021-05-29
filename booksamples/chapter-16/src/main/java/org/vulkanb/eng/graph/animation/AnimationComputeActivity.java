package org.vulkanb.eng.graph.animation;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.vulkanb.eng.EngineProperties;
import org.vulkanb.eng.graph.*;
import org.vulkanb.eng.graph.vk.Queue;
import org.vulkanb.eng.graph.vk.*;
import org.vulkanb.eng.scene.*;

import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.vulkan.VK11.*;

public class AnimationComputeActivity {

    private static final String ANIM_COMPUTE_SHADER_FILE_GLSL = "resources/shaders/animations_comp.glsl";
    private static final String ANIM_COMPUTE_SHADER_FILE_SPV = ANIM_COMPUTE_SHADER_FILE_GLSL + ".spv";
    private static final int LOCAL_SIZE_X = 32;
    private final Queue.ComputeQueue computeQueue;
    private final Device device;
    private final MemoryBarrier memoryBarrier;
    private final Scene scene;

    private CommandBuffer commandBuffer;
    private ComputePipeline computePipeline;
    private DescriptorPool descriptorPool;
    private DescriptorSetLayout[] descriptorSetLayouts;
    private DescriptorSet.StorageDescriptorSet dstVerticesDescriptorSet;
    private Fence fence;
    private DescriptorSet.StorageDescriptorSet jointMatricesDescriptorSet;
    private ShaderProgram shaderProgram;
    private DescriptorSet.StorageDescriptorSet srcVerticesDescriptorSet;
    private DescriptorSetLayout.StorageDescriptorSetLayout storageDescriptorSetLayout;
    private DescriptorSet.StorageDescriptorSet weightsDescriptorSet;

    public AnimationComputeActivity(CommandPool commandPool, PipelineCache pipelineCache, Scene scene) {
        this.scene = scene;
        device = pipelineCache.getDevice();
        computeQueue = new Queue.ComputeQueue(device, 0);
        createDescriptorPool();
        createDescriptorSets();
        createShaders();
        createPipeline(pipelineCache);
        createCommandBuffers(commandPool);
        memoryBarrier = new MemoryBarrier(0, VK_ACCESS_SHADER_WRITE_BIT);
    }

    public void cleanup() {
        computePipeline.cleanup();
        shaderProgram.cleanup();
        commandBuffer.cleanup();
        descriptorPool.cleanup();
        storageDescriptorSetLayout.cleanup();
        fence.cleanup();
    }

    private void createCommandBuffers(CommandPool commandPool) {
        commandBuffer = new CommandBuffer(commandPool, true, false);
        fence = new Fence(device, true);
    }

    private void createDescriptorPool() {
        List<DescriptorPool.DescriptorTypeCount> descriptorTypeCounts = new ArrayList<>();
        descriptorTypeCounts.add(new DescriptorPool.DescriptorTypeCount(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER));
        descriptorPool = new DescriptorPool(device, descriptorTypeCounts);
    }

    private void createDescriptorSets() {
        storageDescriptorSetLayout = new DescriptorSetLayout.StorageDescriptorSetLayout(device, 0, VK_SHADER_STAGE_COMPUTE_BIT);
        descriptorSetLayouts = new DescriptorSetLayout[]{
                storageDescriptorSetLayout,
                storageDescriptorSetLayout,
                storageDescriptorSetLayout,
                storageDescriptorSetLayout,
        };
    }

    private void createPipeline(PipelineCache pipelineCache) {
        ComputePipeline.PipeLineCreationInfo pipeLineCreationInfo = new ComputePipeline.PipeLineCreationInfo(shaderProgram,
                descriptorSetLayouts);
        computePipeline = new ComputePipeline(pipelineCache, pipeLineCreationInfo);
    }

    private void createShaders() {
        EngineProperties engineProperties = EngineProperties.getInstance();
        if (engineProperties.isShaderRecompilation()) {
            ShaderCompiler.compileShaderIfChanged(ANIM_COMPUTE_SHADER_FILE_GLSL, Shaderc.shaderc_compute_shader);
        }
        shaderProgram = new ShaderProgram(device, new ShaderProgram.ShaderModuleData[]
                {
                        new ShaderProgram.ShaderModuleData(VK_SHADER_STAGE_COMPUTE_BIT, ANIM_COMPUTE_SHADER_FILE_SPV),
                });
    }

    public void onAnimatedEntitiesLoaded(GlobalBuffers globalBuffers) {
        srcVerticesDescriptorSet = new DescriptorSet.StorageDescriptorSet(descriptorPool,
                storageDescriptorSetLayout, globalBuffers.getVerticesBuffer(), 0);
        weightsDescriptorSet = new DescriptorSet.StorageDescriptorSet(descriptorPool,
                storageDescriptorSetLayout, globalBuffers.getAnimWeightsBuffer(), 0);
        dstVerticesDescriptorSet = new DescriptorSet.StorageDescriptorSet(descriptorPool,
                storageDescriptorSetLayout, globalBuffers.getAnimInstanceDataBuffer(), 0);
        jointMatricesDescriptorSet = new DescriptorSet.StorageDescriptorSet(descriptorPool,
                storageDescriptorSetLayout, globalBuffers.getAnimJointMatricesBuffer(), 0);
    }

    public void recordCommandBuffer(GlobalBuffers globalBuffers, List<VulkanModel> vulkanModelList) {
        fence.fenceWait();
        fence.reset();

        commandBuffer.reset();
        commandBuffer.beginRecording();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmdHandle = commandBuffer.getVkCommandBuffer();

            vkCmdPipelineBarrier(cmdHandle, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, memoryBarrier.getVkMemoryBarrier(), null, null);

            vkCmdBindPipeline(cmdHandle, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline.getVkPipeline());

            LongBuffer descriptorSets = stack.mallocLong(4);

            descriptorSets.put(0, srcVerticesDescriptorSet.getVkDescriptorSet());
            descriptorSets.put(1, weightsDescriptorSet.getVkDescriptorSet());
            descriptorSets.put(2, dstVerticesDescriptorSet.getVkDescriptorSet());
            descriptorSets.put(3, jointMatricesDescriptorSet.getVkDescriptorSet());
            vkCmdBindDescriptorSets(cmdHandle, VK_PIPELINE_BIND_POINT_COMPUTE,
                    computePipeline.getVkPipelineLayout(), 0, descriptorSets, null);

            for (VulkanModel vulkanModel : vulkanModelList) {
                String modelId = vulkanModel.getModelId();
                List<Entity> entities = scene.getEntitiesByModelId(modelId);
                if (entities == null || entities.isEmpty() || !vulkanModel.hasAnimations()) {
                    continue;
                }

                for (VulkanModel.VulkanMesh mesh : vulkanModel.getVulkanMeshList()) {
                    for (Entity entity : entities) {
                        Entity.EntityAnimation entityAnimation = entity.getEntityAnimation();
                        if (!entityAnimation.isStarted()) {
                            continue;
                        }

                        // TODO: Cache this
                        int vertexSize = 14 * GraphConstants.FLOAT_LENGTH;
                        int groupSize = (int) Math.ceil((mesh.verticesSize() / vertexSize) / (float) LOCAL_SIZE_X);

                        vkCmdDispatch(cmdHandle, groupSize, 1, 1);
                    }
                }
            }
        }
        commandBuffer.endRecording();
    }

    public void submit() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            computeQueue.submit(stack.pointers(commandBuffer.getVkCommandBuffer()),
                    null,
                    null,
                    null,
                    fence);
        }
    }
}