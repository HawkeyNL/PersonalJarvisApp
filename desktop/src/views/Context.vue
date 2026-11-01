<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import PageShell from "../components/PageShell.vue";
import NavIcon from "../components/NavIcon.vue";
import { loadOptional, coreOnline } from "../coreStatus";
import { currentAuthStatus, revokeDevice } from "../auth";
import { homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import { initPlatformCapabilities, platformCapabilities } from "../platform";
import { failureText, type Availability } from "../hubModel";
import { deviceRows, revokeFailureText, type Device, type DeviceRow } from "../devices";

// Devices (canvas D-Devices): the linked devices from GET /v1/devices. A Mac
// revokes another device with a Touch ID approval; elsewhere the list is read-only.
const { title = "Devices", subtitle = "The apps linked to Jarvis" } = defineProps<{ title?: string; subtitle?: string }>();

const devices = ref<Availability<{ devices: Device[] }>>({ state: "loading" });
const thisDevice = ref<string | null>(null);
const signer = computed(() => platformCapabilities.value.supportsBiometricSigning);
const rows = computed(() =>
  devices.value.state === "ok" ? deviceRows(devices.value.value.devices, thisDevice.value, signer.value, Date.now()) : []);

onMounted(async () => {
  void loadHomeNodeConfig().catch(() => {});
  void initPlatformCapabilities().catch(() => {});
  devices.value = await loadOptional<{ devices: Device[] }>("/v1/devices");
  try { thisDevice.value = (await currentAuthStatus()).device_id; } catch { /* browser preview */ }
});

// Revoke: a native modal dialog (focus trap, Esc, inert page); Cancel has focus first.
const dialog = ref<HTMLDialogElement | null>(null);
const confirming = ref<DeviceRow | null>(null);
const revoking = ref(false);
const revokeError = ref("");
const notice = ref("");
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

watch(confirming, (device) => {
  if (device && !dialog.value?.open) dialog.value?.showModal();
  else if (!device && dialog.value?.open) dialog.value.close();
}, { flush: "post" }); // after render, so showModal() finds the autofocus button

function ask(device: DeviceRow) {
  revokeError.value = "";
  confirming.value = device;
}

async function confirmRevoke() {
  const device = confirming.value;
  if (!device || revoking.value) return;
  revoking.value = true;
  revokeError.value = "";
  try {
    await revokeDevice(device.id);
    if (devices.value.state === "ok") {
      devices.value = { state: "ok", value: { devices: devices.value.value.devices.filter((d) => d.id !== device.id) } };
    }
    confirming.value = null;
    clearTimeout(noticeTimer);
    notice.value = `${device.name} revoked. Its sessions have ended.`;
    noticeTimer = setTimeout(() => { notice.value = ""; }, 5000);
  } catch (error) {
    revokeError.value = revokeFailureText(error);
  } finally {
    revoking.value = false;
  }
}

// Esc must not dismiss the dialog while the Touch ID approval is in flight.
function onCancel(event: Event) {
  if (revoking.value) event.preventDefault();
}

onBeforeUnmount(() => clearTimeout(noticeTimer));
</script>

<template>
  <PageShell :title="title" :subtitle="subtitle">
    <section class="card tight" aria-label="Linked devices">
      <p v-if="devices.state === 'loading'" class="state t-sm t-2" role="status">Loading devices…</p>
      <div v-else-if="devices.state !== 'ok'" class="empty" role="status">
        <h3>Devices could not load</h3>
        <p class="t-sm">{{ devices.state === "unsupported" ? "This needs a newer Core." : failureText(devices.reason) }}</p>
      </div>
      <div v-else-if="!rows.length" class="empty"><h3>No devices linked yet</h3></div>
      <div v-else class="scroll">
        <table class="tbl" aria-label="Linked devices">
          <thead><tr><th scope="col">Device</th><th scope="col">Platform</th><th scope="col">Linked</th><th scope="col">Status</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>
                <div class="who">
                  <span class="tile" aria-hidden="true"><NavIcon :name="row.icon" /></span>
                  <span class="name-col">
                    <span class="name">{{ row.name }}</span>
                    <span class="t-xs t-2 mono id">{{ row.id }}</span>
                  </span>
                </div>
              </td>
              <td class="t-sm">{{ row.platform }}</td>
              <td class="t-sm">{{ row.linked || "—" }}</td>
              <td><span class="badge" :class="{ pos: row.active }"><span class="dot"></span>{{ row.status }}</span></td>
              <td class="r">
                <span v-if="row.isThis" class="badge acc">This device</span>
                <button v-else-if="row.canRevoke" type="button" class="btn btn-danger-quiet btn-sm"
                  :aria-label="`Revoke ${row.name}`" @click="ask(row)">Revoke</button>
                <span v-else-if="signer && row.lastApprover" class="t-xs t-2">Last approver</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div class="pair">
      <section class="card col" aria-labelledby="node-heading">
        <h2 id="node-heading">Home Node</h2>
        <dl class="kv t-sm">
          <div><dt class="t-2">Origin</dt><dd class="mono">{{ homeNodeConfig.origin ?? "Not configured" }}</dd></div>
          <div><dt class="t-2">This device</dt><dd class="mono">{{ thisDevice ?? "—" }}</dd></div>
          <div>
            <dt class="t-2">Connection</dt>
            <dd>
              <span class="badge" :class="{ pos: coreOnline === true, dng: coreOnline === false }"><span class="dot"></span>
                {{ coreOnline === null ? "Checking…" : coreOnline ? "Connected" : "Offline" }}</span>
            </dd>
          </div>
        </dl>
      </section>
      <section class="card col" aria-labelledby="approve-heading">
        <h2 id="approve-heading">How approving works</h2>
        <p class="t-sm t-2 prose">
          Account changes, such as revoking a device, are signed on an iOS, Android or macOS device after
          Face ID or Touch ID. Core always keeps one of those approvers: the last one cannot be revoked.
        </p>
        <p v-if="!signer" class="t-sm t-2 prose">
          This computer cannot sign, so the list is read-only here. Revoke from a Mac, or on the Home Node
          with Core Admin: <code>sudo jarvis devices revoke &lt;device id&gt;</code>
        </p>
      </section>
    </div>

    <dialog ref="dialog" class="dialog" aria-labelledby="revoke-title" aria-describedby="revoke-body"
      @cancel="onCancel" @close="confirming = null">
      <template v-if="confirming">
        <h2 id="revoke-title">Revoke {{ confirming.name }}?</h2>
        <p id="revoke-body" class="t-sm t-2">
          {{ confirming.name }} is signed out at once and its sessions end. It can no longer sign in or
          approve anything until you pair it again. Touch ID confirms this.
        </p>
        <p v-if="revokeError" class="err" role="alert">{{ revokeError }}</p>
        <div class="actions">
          <button type="button" class="btn btn-secondary" autofocus :disabled="revoking" @click="confirming = null">Cancel</button>
          <button type="button" class="btn btn-danger" :aria-busy="revoking" @click="confirmRevoke">
            {{ revoking ? "Waiting for Touch ID…" : "Revoke" }}
          </button>
        </div>
      </template>
    </dialog>

    <div class="toast-slot" role="status" aria-live="polite">
      <div v-if="notice" class="toast">{{ notice }}</div>
    </div>
  </PageShell>
</template>

<style scoped>
.card.tight { padding: 4px 8px; }
.state { margin: 0; padding: 16px 12px; }
.empty p { margin: 0; }
.scroll { overflow-x: auto; }
.tbl { min-width: 640px; }
.tbl td { vertical-align: middle; }
.who { display: flex; align-items: center; gap: 12px; min-width: 0; }
.tile :deep(svg) { width: 18px; height: 18px; }
.name-col { display: flex; flex-direction: column; min-width: 0; }
.name { font-weight: 500; overflow-wrap: anywhere; }
.id { overflow-wrap: anywhere; user-select: text; }
.pair { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }
.pair > .card { flex: 1 1 420px; min-width: 0; }
.col { display: flex; flex-direction: column; gap: 10px; }
.kv { margin: 0; display: flex; flex-direction: column; gap: 8px; }
.kv > div { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.kv dd { margin: 0; min-width: 0; text-align: right; overflow-wrap: anywhere; user-select: text; }
.prose { margin: 0; line-height: 1.6; }
.prose code { user-select: text; overflow-wrap: anywhere; }
.dialog h2, .dialog p { margin: 0; }
.actions { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.toast-slot { position: fixed; left: 50%; bottom: 28px; transform: translateX(-50%); z-index: 60; }
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; border: 0;
  overflow: hidden; clip-path: inset(50%); white-space: nowrap;
}
</style>
