import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, deleteDoc, writeBatch, collection, getDocs, serverTimestamp, getCountFromServer, query, orderBy, limit } from 'firebase/firestore';
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
await t('alice adds a photo', setDoc(doc(A, 'profiles/alice'), { name: 'Alice', handle: 'alice', photo: 'x'.repeat(40000) }), true);
await t('photo only (no username yet)', setDoc(doc(C, 'profiles/carol'), { photo: 'x'.repeat(1000) }), true);
await t('huge photo', setDoc(doc(A, 'profiles/alice'), { name: 'Alice', handle: 'alice', photo: 'x'.repeat(200000) }), false);
await t('photo not text', setDoc(doc(A, 'profiles/alice'), { name: 'Alice', handle: 'alice', photo: 5 }), false);
await t('bob changes alice photo', setDoc(doc(B, 'profiles/alice'), { photo: 'y' }), false);
await t('bob sees alice photo', getDoc(doc(B, 'profiles/alice')), true);
await t('bob deletes alice profile', deleteDoc(doc(B, 'profiles/alice')), false);
await t('alice deletes own profile', deleteDoc(doc(A, 'profiles/alice')), true);
await t('alice deletes own stats', deleteDoc(doc(A, 'users/alice/public/stats')), true);
await t('follow self', setDoc(doc(A, 'users/alice/following/alice'), { uid: 'alice' }), false);
// --- Feed ---
const D = db('dave'), E = db('erin');
await follow(D, 'dave', 'erin'); await follow(E, 'erin', 'dave');      // dave and erin are friends
await t('dave posts', setDoc(doc(D, 'users/dave/posts/p1'), { text: 'Leg day', workout: '{}', createdAt: serverTimestamp() }), true);
await t('post with a photo', setDoc(doc(D, 'users/dave/posts/p2'), { photo: 'x'.repeat(300000), createdAt: serverTimestamp() }), true);
await t('photo too big', setDoc(doc(D, 'users/dave/posts/p3'), { photo: 'x'.repeat(500000), createdAt: serverTimestamp() }), false);
await t('empty post', setDoc(doc(D, 'users/dave/posts/p4'), { createdAt: serverTimestamp() }), false);
await t('backdated post', setDoc(doc(D, 'users/dave/posts/p5'), { text: 'x', createdAt: new Date(2020, 1, 1) }), false);
await t('extra field', setDoc(doc(D, 'users/dave/posts/p6'), { text: 'x', likes: 999, createdAt: serverTimestamp() }), false);
await t('erin posts as dave', setDoc(doc(E, 'users/dave/posts/p7'), { text: 'x', createdAt: serverTimestamp() }), false);
await t('dave edits post', setDoc(doc(D, 'users/dave/posts/p1'), { text: 'edited', createdAt: serverTimestamp() }), false);
await t('friend reads feed', getDocs(query(collection(E, 'users/dave/posts'), orderBy('createdAt', 'desc'), limit(15))), true);
await t('stranger reads feed', getDocs(collection(C, 'users/dave/posts')), false);
await t('stranger reads a post', getDoc(doc(C, 'users/dave/posts/p1')), false);
await t('friend likes', setDoc(doc(E, 'users/dave/posts/p1/likes/erin'), { at: serverTimestamp() }), true);
await t('like as someone else', setDoc(doc(E, 'users/dave/posts/p1/likes/carol'), { at: serverTimestamp() }), false);
await t('stranger likes', setDoc(doc(C, 'users/dave/posts/p1/likes/carol'), { at: serverTimestamp() }), false);
await t('like a missing post', setDoc(doc(E, 'users/dave/posts/nope/likes/erin'), { at: serverTimestamp() }), false);
await t('author likes own', setDoc(doc(D, 'users/dave/posts/p1/likes/dave'), { at: serverTimestamp() }), true);
await t('friend reads likes', getDocs(collection(E, 'users/dave/posts/p1/likes')), true);
await t('stranger reads likes', getDocs(collection(C, 'users/dave/posts/p1/likes')), false);
await t('friend comments', setDoc(doc(E, 'users/dave/posts/p1/comments/c1'), { uid: 'erin', text: 'Strong!', at: serverTimestamp() }), true);
await t('comment as someone else', setDoc(doc(E, 'users/dave/posts/p1/comments/c2'), { uid: 'dave', text: 'x', at: serverTimestamp() }), false);
await t('empty comment', setDoc(doc(E, 'users/dave/posts/p1/comments/c3'), { uid: 'erin', text: '', at: serverTimestamp() }), false);
await t('long comment', setDoc(doc(E, 'users/dave/posts/p1/comments/c4'), { uid: 'erin', text: 'x'.repeat(501), at: serverTimestamp() }), false);
await t('stranger comments', setDoc(doc(C, 'users/dave/posts/p1/comments/c5'), { uid: 'carol', text: 'hi', at: serverTimestamp() }), false);
await t('comment count', getCountFromServer(collection(E, 'users/dave/posts/p1/comments')), true);
await t('stranger counts comments', getCountFromServer(collection(C, 'users/dave/posts/p1/comments')), false);
await t('author deletes friend comment', setDoc(doc(E, 'users/dave/posts/p1/comments/c6'), { uid: 'erin', text: 'bye', at: serverTimestamp() }).then(() => deleteDoc(doc(D, 'users/dave/posts/p1/comments/c6'))), true);
await t('friend deletes own comment', deleteDoc(doc(E, 'users/dave/posts/p1/comments/c1')), true);
await t('friend deletes author like', deleteDoc(doc(E, 'users/dave/posts/p1/likes/dave')), false);
await t('author clears a like', deleteDoc(doc(D, 'users/dave/posts/p1/likes/erin')), true);
await t('friend deletes post', deleteDoc(doc(E, 'users/dave/posts/p1')), false);
await t('author deletes post', deleteDoc(doc(D, 'users/dave/posts/p1')), true);
await t('after unfollow, no reading', writeBatch(E).delete(doc(E, 'users/erin/following/dave')).delete(doc(E, 'users/dave/followers/erin')).commit()
  .then(() => getDoc(doc(E, 'users/dave/posts/p2'))), false);
console.log(`${n - bad}/${n} rule checks passed`);
await env.cleanup();
