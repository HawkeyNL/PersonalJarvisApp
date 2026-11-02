<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from "vue";
import { accountStatus, setAccountPassword } from "../auth";
import { platformCapabilities } from "../platform";
// Only a Mac signs account changes, behind Touch ID. Devices are revoked on the
// Devices page, which also refuses this device and the last approver.
const signer = computed(() => platformCapabilities.value.supportsBiometricSigning);
const passwordRequired = ref(true);
const currentPassword = ref("");
const newPassword = ref("");
const busy = ref(false);
const message = ref("");
async function refresh() {
  try {
    passwordRequired.value = (await accountStatus()).password_required;
  } catch { message.value = "Sign in to manage your account."; }
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
    message.value = "Password changed. Sign in again on your devices.";
  } catch { message.value = "Change not completed. Check your password and confirm with Touch ID."; }
  finally { busy.value = false; }
}
onMounted(refresh);
onBeforeUnmount(() => { currentPassword.value = ""; newPassword.value = ""; });
</script>

<template>
  <section class="card account-administration" aria-labelledby="account-admin-heading">
    <h2 id="account-admin-heading">Account password</h2>
    <template v-if="signer">
      <p class="t-sm t-2">A password never replaces the signed approval of a trusted device. Every change asks for Touch ID.</p>
      <form @submit.prevent="changePassword">
        <div v-if="passwordRequired" class="field">
          <label class="label" for="account-current-password">Current password</label>
          <input id="account-current-password" v-model="currentPassword" class="input" type="password" autocomplete="current-password" maxlength="1024" required />
        </div>
        <div class="field">
          <label class="label" for="account-new-password">New account password</label>
          <input id="account-new-password" v-model="newPassword" class="input" type="password" autocomplete="new-password" minlength="15" maxlength="1024" required />
        </div>
        <div><button class="btn btn-primary" :disabled="busy">Set and sign password</button></div>
      </form>
    </template>
    <p v-else class="t-sm t-2">Change the account password on a Mac with Touch ID.</p>
    <p class="t-sm t-2">Trusted devices are listed, and revoked on a Mac, on the <RouterLink to="/devices">Devices</RouterLink> page.</p>
    <p v-if="message" class="t-sm" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.account-administration { display: flex; flex-direction: column; gap: 14px; overflow-wrap: anywhere; }
h2, p { margin: 0; }
form { display: flex; flex-direction: column; gap: 14px; max-width: 28rem; }
a { color: var(--accent-text); }
</style>
