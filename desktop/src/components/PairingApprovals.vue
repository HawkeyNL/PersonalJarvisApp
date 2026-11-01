<script setup lang="ts">
import { computed } from "vue";
import { pairingError, pairingRequests, approvePairing, denyPairing } from "../pairingApprovals";
import { displayHint, groupFingerprint } from "../pairingFingerprint";
import { platformCapabilities } from "../platform";

const time = (seconds: number) => new Date(seconds * 1000).toLocaleString();
// Only a Mac approves here, behind Touch ID; other desktops can deny.
const signer = computed(() => platformCapabilities.value.supportsBiometricSigning);
</script>
<template>
  <aside v-if="pairingRequests.length" class="pairing-approvals">
    <section v-for="request in pairingRequests" :key="request.id" class="request">
      <strong>New device: {{ displayHint(request.device_name, 128) }}</strong>
      <dl>
        <dt>Platform</dt><dd>{{ displayHint(request.platform, 32) }}</dd>
        <dt>Model</dt><dd>{{ displayHint(request.model) }}</dd>
        <dt>App version</dt><dd>{{ displayHint(request.app_version, 32) }}</dd>
        <dt>IP address</dt><dd>{{ displayHint(request.ip) }}</dd>
        <dt>User agent</dt><dd>{{ displayHint(request.user_agent, 128) }}</dd>
        <dt>Requested</dt><dd>{{ time(request.created_at) }}</dd>
        <dt>Expires</dt><dd>{{ time(request.expires_at) }}</dd>
        <dt>Key fingerprint</dt>
        <dd v-if="request.verified_fingerprint" class="fingerprint">{{ groupFingerprint(request.verified_fingerprint) }}</dd>
        <dd v-else class="error">Does not match the candidate key. Do not approve.</dd>
      </dl>
      <small>{{ signer ? "Approving asks for Touch ID." : "Approve from your phone or a Mac with Touch ID." }} Compare the fingerprint with the one the new device shows. Model, version, IP and user agent are unverified hints.</small>
      <div>
        <button v-if="signer" class="btn btn-primary" :disabled="!request.verified_fingerprint" @click="approvePairing(request)">Approve</button>
        <button class="btn btn-secondary" @click="denyPairing(request)">Deny</button>
      </div>
    </section>
    <small v-if="pairingError" class="error">{{ pairingError }}</small>
  </aside>
</template>
<style scoped>
.request { max-width: 440px; overflow-wrap: anywhere; }
dl { display: grid; grid-template-columns: max-content 1fr; gap: 2px 10px; margin: 6px 0; }
dt { color: var(--muted); }
dd { margin: 0; }
.fingerprint { font-family: var(--mono); }
.error { color: var(--danger-text); }
</style>
