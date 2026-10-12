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
    <p>Changing the account password is not available yet. To revoke a device, run <code>sudo jarvis devices revoke &lt;id&gt;</code> on the Home Node.</p>
    <h3>Trusted devices</h3>
    <button @click="refresh">Refresh</button>
    <ul>
      <li v-for="device in devices" :key="device.id">
        <span>{{ device.name }} · {{ device.platform }} · {{ device.status }} · <code>{{ device.id }}</code></span>
      </li>
    </ul>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.account-administration { overflow-wrap: anywhere; }
li { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; margin-block: .5rem; }
</style>
