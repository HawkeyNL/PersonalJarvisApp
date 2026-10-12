<script setup lang="ts">
import { ref, onMounted } from "vue";
import { listDevices, type DeviceItem } from "../auth";
const devices = ref<DeviceItem[]>([]);
const message = ref("");
async function refresh() {
  try {
    devices.value = await listDevices();
  } catch { message.value = "Sign in to see your devices."; }
}
onMounted(refresh);
</script>

<template>
  <section class="panel glass account-administration">
    <h2>Account and devices</h2>
    <p>Approve from your phone: password changes and device revocation need a phone approval. Core Admin on the Home Node can also revoke devices.</p>
    <h3>Trusted devices</h3>
    <button @click="refresh">Refresh</button>
    <ul>
      <li v-for="device in devices" :key="device.id">
        <span>{{ device.name }} · {{ device.platform }} · {{ device.status }}</span>
      </li>
    </ul>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.account-administration { overflow-wrap: anywhere; }
li { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; margin-block: .5rem; }
</style>
