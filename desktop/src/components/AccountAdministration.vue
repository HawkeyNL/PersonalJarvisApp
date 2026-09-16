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
  } catch { message.value = "Meld je aan om je apparaten te beheren."; }
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
    message.value = "Wachtwoord gewijzigd. Meld je opnieuw aan op je apparaten.";
  } catch { message.value = "Wijziging niet voltooid. Controleer je wachtwoord en bevestig met je apparaat."; }
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
    message.value = "Apparaat ingetrokken. De bijbehorende sessies zijn beëindigd.";
    confirmation.value = null;
  } catch { message.value = "Intrekken niet voltooid. Er is een geldige apparaattoestemming nodig."; }
  finally { busy.value = false; }
}
onMounted(refresh);
onBeforeUnmount(() => { currentPassword.value = ""; newPassword.value = ""; });
</script>

<template>
  <section class="panel glass account-administration">
    <h2>Account en apparaten</h2>
    <p>Een wachtwoord vervangt nooit de ondertekende goedkeuring van een vertrouwd apparaat.</p>
    <form @submit.prevent="changePassword">
      <label v-if="passwordRequired">Huidig wachtwoord
        <input v-model="currentPassword" type="password" autocomplete="current-password" maxlength="1024" required />
      </label>
      <label>Nieuw accountwachtwoord
        <input v-model="newPassword" type="password" autocomplete="new-password" minlength="15" maxlength="1024" required />
      </label>
      <button :disabled="busy">Wachtwoord instellen en ondertekenen</button>
    </form>
    <h3>Vertrouwde apparaten</h3>
    <button :disabled="busy" @click="refresh">Vernieuwen</button>
    <ul>
      <li v-for="device in devices" :key="device.id">
        <span>{{ device.name }} · {{ device.platform }} · {{ device.status }}</span>
        <button v-if="device.status === 'active'" :disabled="busy" @click="confirmation = device">Intrekken…</button>
      </li>
    </ul>
    <div v-if="confirmation" role="alertdialog" aria-label="Apparaat intrekken">
      <p>{{ confirmation.name }} intrekken? Dit beëindigt de sessies van dat apparaat.</p>
      <button :disabled="busy" @click="confirmation = null">Annuleren</button>
      <button :disabled="busy" @click="confirmRevoke">Bevestigen en ondertekenen</button>
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
