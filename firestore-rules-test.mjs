import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, deleteDoc, writeBatch, collection, getDocs } from 'firebase/firestore';
import { readFileSync } from 'fs';
const env = await initializeTestEnvironment({ projectId: 'demo-kilonaut', firestore: { rules: readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8089 } });
const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();
const A = db('alice'), B = db('bob'), C = db('carol'), anon = db(null);
let n = 0, bad = 0;
async function t(name, p, ok) { n++; try { await (ok ? assertSucceeds(p) : assertFails(p)); } catch (e) { bad++; console.log('FAIL:', name, e.message.split('\n')[0]); } }
const follow = (d, me, other) => writeBatch(d).set(doc(d, `users/${me}/following/${other}`), { uid: other }).set(doc(d, `users/${other}/followers/${me}`), { uid: me }).commit();

// Own data
await t('alice writes own workout', setDoc(doc(A, 'users/alice/workouts/1'), { data: 'x' }), true);
await t('bob reads alice workout', getDoc(doc(B, 'users/alice/workouts/1')), false);
await t('anon reads profile', getDoc(doc(anon, 'profiles/alice')), false);
// Usernames
await t('alice claims handle', setDoc(doc(A, 'handles/alice'), { uid: 'alice' }), true);
await t('bob steals alice handle', setDoc(doc(B, 'handles/alice'), { uid: 'bob' }), false);
await t('bob deletes alice handle', deleteDoc(doc(B, 'handles/alice')), false);
await t('bad handle', setDoc(doc(B, 'handles/Bad Name'), { uid: 'bob' }), false);
await t('handle for someone else', setDoc(doc(B, 'handles/carolx'), { uid: 'carol' }), false);
await t('alice profile', setDoc(doc(A, 'profiles/alice'), { name: 'Alice', handle: 'alice' }), true);
await t('bob writes alice profile', setDoc(doc(B, 'profiles/alice'), { name: 'X', handle: 'x' }), false);
await t('profile extra field', setDoc(doc(A, 'profiles/alice'), { name: 'Alice', handle: 'alice', email: 'a@b' }), false);
await t('bob reads alice profile', getDoc(doc(B, 'profiles/alice')), true);
// Stats
await t('alice writes stats', setDoc(doc(A, 'users/alice/public/stats'), { data: '{}' }), true);
await t('alice writes other public doc', setDoc(doc(A, 'users/alice/public/other'), { data: '{}' }), false);
await t('bob writes alice stats', setDoc(doc(B, 'users/alice/public/stats'), { data: '{}' }), false);
await t('stranger reads stats', getDoc(doc(B, 'users/alice/public/stats')), false);
// Following
await t('bob follows alice', follow(B, 'bob', 'alice'), true);
await t('bob fakes alice following him', setDoc(doc(B, 'users/alice/following/bob'), { uid: 'bob' }), false);
await t('bob fakes a follower entry for carol', setDoc(doc(B, 'users/alice/followers/carol'), { uid: 'carol' }), false);
await t('one-way: bob reads alice stats', getDoc(doc(B, 'users/alice/public/stats')), false);
await t('alice sees request', getDocs(collection(A, 'users/alice/followers')), true);
await t('carol reads alice followers', getDocs(collection(C, 'users/alice/followers')), false);
await t('alice accepts (follows back)', follow(A, 'alice', 'bob'), true);
await t('friends: bob reads alice stats', getDoc(doc(B, 'users/alice/public/stats')), true);
await t('friends: bob still cannot read workouts', getDoc(doc(B, 'users/alice/workouts/1')), false);
await t('carol reads alice stats', getDoc(doc(C, 'users/alice/public/stats')), false);
await t('carol follows only: still no', follow(C, 'carol', 'alice').then(() => getDoc(doc(C, 'users/alice/public/stats'))), false);
// Removing
await t('alice removes carol follow', writeBatch(A).delete(doc(A, 'users/alice/followers/carol')).delete(doc(A, 'users/carol/following/alice')).commit(), true);
await t('carol deletes alice following bob', deleteDoc(doc(C, 'users/alice/following/bob')), false);
await t('bob unfollows', writeBatch(B).delete(doc(B, 'users/bob/following/alice')).delete(doc(B, 'users/alice/followers/bob')).commit(), true);
await t('after unfollow bob cannot read stats', getDoc(doc(B, 'users/alice/public/stats')), false);
await t('bob deletes alice profile', deleteDoc(doc(B, 'profiles/alice')), false);
await t('alice deletes own profile', deleteDoc(doc(A, 'profiles/alice')), true);
await t('alice deletes own stats', deleteDoc(doc(A, 'users/alice/public/stats')), true);
await t('follow self', setDoc(doc(A, 'users/alice/following/alice'), { uid: 'alice' }), false);
console.log(`${n - bad}/${n} rule checks passed`);
await env.cleanup();
