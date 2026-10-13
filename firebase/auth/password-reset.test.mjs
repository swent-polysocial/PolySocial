// Contributors: OpenAI Codex (local Auth emulator password-reset proof for #164).
import test from 'node:test';
import assert from 'node:assert/strict';

test('reset replaces the password and proves email ownership without changing the UID', async () => {
  const host = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  assert(host && /^(localhost|127\.0\.0\.1):\d+$/.test(host), 'Local Auth emulator required');
  const project = 'demo-polysocial';
  const base = `http://${host}/identitytoolkit.googleapis.com/v1/`;
  async function call(path, body) {
    const response = await fetch(base + path + '?key=fake-key', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    return { status: response.status, data: await response.json() };
  }
  await fetch(`http://${host}/emulator/v1/projects/${project}/accounts`, { method: 'DELETE' });
  const email = 'recovery.student@epfl.ch';
  const created = await call('accounts:signUp', {
    email, password: 'OldPassword123', returnSecureToken: true,
  });
  assert.equal(created.status, 200);
  const before = await call('accounts:lookup', { idToken: created.data.idToken });
  assert.equal(before.data.users[0].emailVerified, false);
  const request = await call('accounts:sendOobCode', { requestType: 'PASSWORD_RESET', email });
  assert.equal(request.status, 200);
  const afterRequest = await call('accounts:lookup', { idToken: created.data.idToken });
  assert.equal(afterRequest.data.users[0].emailVerified, false);
  const { oobCodes } = await (
    await fetch(`http://${host}/emulator/v1/projects/${project}/oobCodes`)
  ).json();
  const reset = oobCodes.find(c => c.email === email && c.requestType === 'PASSWORD_RESET');
  assert(reset && reset.oobLink, 'Reset browser link exists');
  const code = new URL(reset.oobLink).searchParams.get('oobCode');
  assert.equal((await call('accounts:resetPassword', { oobCode: code })).status, 200);
  const resetResult = await call('accounts:resetPassword', {
    oobCode: code, newPassword: 'NewPassword456',
  });
  assert.equal(resetResult.status, 200);
  const oldLogin = await call('accounts:signInWithPassword', {
    email, password: 'OldPassword123', returnSecureToken: true,
  });
  assert.equal(oldLogin.status, 400);
  assert.equal(oldLogin.data.error.message, 'INVALID_PASSWORD');
  const changed = await call('accounts:signInWithPassword', {
    email, password: 'NewPassword456', returnSecureToken: true,
  });
  assert.equal(changed.status, 200);
  assert.equal(changed.data.localId, created.data.localId);
  const profile = await call('accounts:lookup', { idToken: changed.data.idToken });
  assert.equal(profile.data.users[0].emailVerified, true);
});
