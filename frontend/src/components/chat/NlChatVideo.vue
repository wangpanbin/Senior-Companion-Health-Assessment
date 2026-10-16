<script setup>
/** M13 原生视频预览：仅读取元数据，不自动下载/播放整个附件。 */
import { ref, watch } from 'vue'
const props = defineProps({ src: { type: String, required: true } })
const failed = ref(false)
watch(
  () => props.src,
  () => {
    failed.value = false
  }
)
</script>

<template>
  <div class="chat-video">
    <p v-if="!src || failed" role="status">视频暂不可用，请刷新后重试</p>
    <video
      v-else
      :key="src"
      :src="src"
      controls
      preload="metadata"
      playsinline
      aria-label="聊天视频"
      @error="failed = true"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;
.chat-video {
  video {
    display: block;
    width: 100%;
    max-height: 45vh;
    border-radius: var(--nl-radius-card);
  }
  p {
    font-size: var(--nl-font-body);
    color: var(--nl-text-2);
  }
}
</style>
