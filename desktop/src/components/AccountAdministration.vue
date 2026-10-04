<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
import { accountStatus, listDevices, revokeDevice, setAccountPassword, type DeviceItem } from "../auth";
const devices = ref<DeviceItem[]>([]);
const passwordRequired = ref(true);
const currentPassword = ref("");
const newPassword = ref("");
const confirmation = ref<DeviceItem | null>(null);
const busy = ref(false);
const message = ref("");
async function refresh() {
  try {
    const [account, knownDevices] = await Promise.all([accountStatus(), listDevices()]);
    passwordRequired.value = account.password_required;
    devices.value = knownDevices;
  } catch { message.value = "Sign in to manage your devices."; }
}
async function changePassword() {
  busy.value = true;
  message.value = "";
  const previous = currentPassword.value;
  const next = newPassword.value;
  currentPassword.value = "";
  newPassword.value = "";
  try {
    await setAccountPassword(next, previous || undefined);
    devices.value = [];
    message.value = "Password changed. Sign in again on your devices.";
  } catch { message.value = "Change not completed. Check your password and confirm on your device."; }
  finally { busy.value = false; }
}
async function confirmRevoke() {
  const device = confirmation.value;
  if (!device) return;
  busy.value = true;
  message.value = "";
  try {
    await revokeDevice(device.id);
    devices.value = devices.value.filter(item => item.id !== device.id);
    message.value = "Device revoked. Its sessions have ended.";
    confirmation.value = null;
  } catch { message.value = "Revocation not completed. A valid device approval is required."; }
  finally { busy.value = false; }
}
onMounted(refresh);
onBeforeUnmount(() => { currentPassword.value = ""; newPassword.value = ""; });
</script>

<template>
  <section class="panel glass account-administration">
    <h2>Account and devices</h2>
    <p>A password never replaces the signed approval of a trusted device.</p>
    <form @submit.prevent="changePassword">
      <label v-if="passwordRequired">Current password
        <input v-model="currentPassword" type="password" autocomplete="current-password" maxlength="1024" required />
      </label>
      <label>New account password
        <input v-model="newPassword" type="password" autocomplete="new-password" minlength="15" maxlength="1024" required />
      </label>
      <button :disabled="busy">Set and sign password</button>
    </form>
    <h3>Trusted devices</h3>
    <button :disabled="busy" @click="refresh">Refresh</button>
    <ul>
      <li v-for="device in devices" :key="device.id">
        <span>{{ device.name }} · {{ device.platform }} · {{ device.status }}</span>
        <button v-if="device.status === 'active'" :disabled="busy" @click="confirmation = device">Revoke…</button>
      </li>
    </ul>
    <div v-if="confirmation" role="alertdialog" aria-label="Revoke device">
      <p>Revoke {{ confirmation.name }}? This ends that device's sessions.</p>
      <button :disabled="busy" @click="confirmation = null">Cancel</button>
      <button :disabled="busy" @click="confirmRevoke">Confirm and sign</button>
    </div>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.account-administration { overflow-wrap: anywhere; }
label { display: block; margin-block: .8rem; }
input { display: block; width: min(100%, 28rem); box-sizing: border-box; }
li { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; margin-block: .5rem; }
</style>
