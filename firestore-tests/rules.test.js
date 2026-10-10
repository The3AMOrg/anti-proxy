const fs = require('fs');
const path = require('path');

const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch,
  collection, query, where, serverTimestamp,
} = require('firebase/firestore');

const TEACHER = 'teacher-uid';
const STUDENT = 'student-uid';
const OTHER = 'other-uid';
const CID = 'class-1';
const CODE = 'K7M4QX';
const KEY = 'B'.repeat(88);

let testEnv;

const PHOTO = 'https://lh3.googleusercontent.com/a/riya-photo';

const googleUser = (uid, name, picture) =>
  testEnv.authenticatedContext(uid, {
    name,
    ...(picture ? { picture } : {}),
    firebase: { sign_in_provider: 'google.com', identities: {} },
  }).firestore();

const teacher = () => googleUser(TEACHER, 'Dr X');
const student = () => googleUser(STUDENT, 'Riya');
const other = () => googleUser(OTHER, 'Sam');
const anonymous = () =>
  testEnv.authenticatedContext(OTHER, {
    firebase: { sign_in_provider: 'anonymous', identities: {} },
  }).firestore();

const classroomData = (overrides = {}) => ({
  name: 'Physics 101',
  teacherUid: TEACHER,
  teacherName: 'Dr X',
  authorityPublicKey: KEY,
  joinCode: CODE,
  createdAt: serverTimestamp(),
  ...overrides,
});

/** Classroom + its join code, written together the way the app does it. */
function createClassroom(db, overrides = {}) {
  const data = classroomData(overrides);
  const batch = writeBatch(db);
  batch.set(doc(db, 'classrooms', CID), data);
  batch.set(doc(db, 'joinCodes', data.joinCode), { classroomId: CID, teacherUid: TEACHER });
  return batch.commit();
}

/** Member + the student's own mirror, written together the way the app does it. */
function join(db, uid, name, code = CODE) {
  const batch = writeBatch(db);
  batch.set(doc(db, 'classrooms', CID, 'members', uid), {
    name, devicePublicKey: KEY, joinCode: code, joinedAt: serverTimestamp(),
  });
  batch.set(doc(db, 'users', uid, 'classrooms', CID), { joinedAt: serverTimestamp() });
  return batch.commit();
}

/** Arrange state with rules off. */
async function seed(fn) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));
}

const seedClassroom = () => seed((db) => createClassroom(db));
const seedMember = (uid = STUDENT, name = 'Riya') =>
  seed((db) => setDoc(doc(db, 'classrooms', CID, 'members', uid), {
    name, devicePublicKey: KEY, joinCode: CODE, joinedAt: new Date(),
  }));

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-antiproxy',
    firestore: {
      rules: fs.readFileSync(path.resolve(__dirname, '../firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await testEnv.cleanup(); });
beforeEach(async () => { await testEnv.clearFirestore(); });

describe('profiles', () => {
  it('lets a user write their own name', async () => {
    await assertSucceeds(setDoc(doc(student(), 'users', STUDENT), { name: 'Riya' }));
  });

  it("refuses writing someone else's profile", async () => {
    await assertFails(setDoc(doc(student(), 'users', OTHER), { name: 'Riya' }));
  });
});

describe('creating classrooms', () => {
  it('lets a Google user create a classroom with its join code', async () => {
    await assertSucceeds(createClassroom(teacher()));
  });

  it('refuses anonymous users', async () => {
    const db = anonymous();
    const batch = writeBatch(db);
    batch.set(doc(db, 'classrooms', CID), classroomData({ teacherUid: OTHER }));
    batch.set(doc(db, 'joinCodes', CODE), { classroomId: CID, teacherUid: OTHER });
    await assertFails(batch.commit());
  });

  it('refuses a teacher name that differs from the Google account', async () => {
    await assertFails(createClassroom(teacher(), { teacherName: 'Prof Famous' }));
  });

  it('refuses codes with look-alike characters', async () => {
    await assertFails(createClassroom(teacher(), { joinCode: 'K0M1QX' }));
  });

  it("refuses a join code pointing at another teacher's classroom", async () => {
    await seedClassroom();
    await assertFails(setDoc(doc(other(), 'joinCodes', 'ABCDEF'), { classroomId: CID, teacherUid: OTHER }));
  });

  it('refuses a classroom without its join code in the same batch', async () => {
    await assertFails(setDoc(doc(teacher(), 'classrooms', CID), classroomData()));
  });
});

describe('invites', () => {
  beforeEach(seedClassroom);

  it('lets any Google user look up a code and the classroom it points to', async () => {
    const db = student();
    await assertSucceeds(getDoc(doc(db, 'joinCodes', CODE)));
    await assertSucceeds(getDoc(doc(db, 'classrooms', CID)));
  });

  it('refuses listing join codes', async () => {
    await assertFails(getDocs(collection(student(), 'joinCodes')));
  });

  it("refuses listing other teachers' classrooms", async () => {
    await assertFails(getDocs(query(collection(student(), 'classrooms'), where('teacherUid', '==', TEACHER))));
  });

  it('lets a teacher list their own classrooms', async () => {
    await assertSucceeds(getDocs(query(collection(teacher(), 'classrooms'), where('teacherUid', '==', TEACHER))));
  });

  it('refuses non-teachers editing or deleting a classroom', async () => {
    await assertFails(updateDoc(doc(student(), 'classrooms', CID), { name: 'Hijacked' }));
    await assertFails(deleteDoc(doc(student(), 'classrooms', CID)));
  });

  it('lets the teacher rename the classroom', async () => {
    await assertSucceeds(updateDoc(doc(teacher(), 'classrooms', CID), { name: 'Physics 102' }));
  });
});

describe('joining', () => {
  beforeEach(seedClassroom);

  it('lets a student join with the current code', async () => {
    await assertSucceeds(join(student(), STUDENT, 'Riya'));
  });

  it('refuses a wrong code', async () => {
    await assertFails(join(student(), STUDENT, 'Riya', 'ABCDEF'));
  });

  it('refuses joining on behalf of someone else', async () => {
    await assertFails(join(student(), OTHER, 'Riya'));
  });

  it('refuses a member name that differs from the Google account', async () => {
    await assertFails(join(student(), STUDENT, 'Someone Else'));
  });

  it('lets the teacher reset the code, after which only the new code works', async () => {
    const db = teacher();
    const batch = writeBatch(db);
    batch.set(doc(db, 'joinCodes', 'ABCDEF'), { classroomId: CID, teacherUid: TEACHER });
    batch.update(doc(db, 'classrooms', CID), { joinCode: 'ABCDEF' });
    batch.delete(doc(db, 'joinCodes', CODE));
    await assertSucceeds(batch.commit());

    await assertFails(join(student(), STUDENT, 'Riya', CODE));
    await assertSucceeds(join(student(), STUDENT, 'Riya', 'ABCDEF'));
  });

  it("refuses overwriting a student's classroom list entry (the app skips it on re-join)", async () => {
    await assertSucceeds(join(student(), STUDENT, 'Riya'));
    await assertFails(setDoc(doc(student(), 'users', STUDENT, 'classrooms', CID), { joinedAt: serverTimestamp() }));
  });

  it('refuses a classroom mirror without a membership', async () => {
    await assertFails(setDoc(doc(student(), 'users', STUDENT, 'classrooms', CID), { joinedAt: serverTimestamp() }));
  });
});

describe('members and device keys', () => {
  beforeEach(async () => { await seedClassroom(); await seedMember(); });

  it('lets a student register a new phone as pending', async () => {
    await assertSucceeds(updateDoc(doc(student(), 'classrooms', CID, 'members', STUDENT), { pendingDevicePublicKey: 'C'.repeat(88) }));
  });

  it('refuses a student replacing their approved key', async () => {
    await assertFails(updateDoc(doc(student(), 'classrooms', CID, 'members', STUDENT), { devicePublicKey: 'C'.repeat(88) }));
  });

  it('lets the teacher approve the new phone', async () => {
    await assertSucceeds(updateDoc(doc(teacher(), 'classrooms', CID, 'members', STUDENT), {
      devicePublicKey: 'C'.repeat(88), pendingDevicePublicKey: '',
    }));
  });

  it('lets the teacher list members', async () => {
    await assertSucceeds(getDocs(collection(teacher(), 'classrooms', CID, 'members')));
  });

  it("lets a classmate read another member, but not someone outside the class", async () => {
    await seedMember(OTHER, 'Sam');
    await assertSucceeds(getDoc(doc(student(), 'classrooms', CID, 'members', OTHER)));
    await assertFails(getDoc(doc(googleUser('stranger', 'Zed'), 'classrooms', CID, 'members', OTHER)));
  });

  it('lets a student leave and the teacher remove', async () => {
    await assertSucceeds(deleteDoc(doc(student(), 'classrooms', CID, 'members', STUDENT)));
    await seedMember();
    await assertSucceeds(deleteDoc(doc(teacher(), 'classrooms', CID, 'members', STUDENT)));
  });
});

describe('deleting a classroom', () => {
  beforeEach(async () => {
    await seedClassroom();
    await seedMember();
  });

  // What the app does: mark deleted and drop the join code in one batch.
  const tombstone = (db) => {
    const batch = writeBatch(db);
    batch.update(doc(db, 'classrooms', CID), { deleted: true });
    batch.delete(doc(db, 'joinCodes', CODE));
    return batch.commit();
  };

  it('lets the teacher mark the classroom deleted, then remove its members', async () => {
    await assertSucceeds(tombstone(teacher()));
    await assertSucceeds(deleteDoc(doc(teacher(), 'classrooms', CID, 'members', STUDENT)));
  });

  it('refuses students marking the classroom deleted', async () => {
    await assertFails(tombstone(student()));
  });

  it('refuses undeleting or renaming a deleted classroom', async () => {
    await assertSucceeds(tombstone(teacher()));
    await assertFails(updateDoc(doc(teacher(), 'classrooms', CID), { deleted: false }));
    await assertFails(updateDoc(doc(teacher(), 'classrooms', CID), { name: 'Physics 102' }));
  });

  it('lets students still read the deleted classroom, so the notice can say why', async () => {
    await assertSucceeds(tombstone(teacher()));
    await assertSucceeds(getDoc(doc(student(), 'classrooms', CID)));
  });

  it('refuses joining a deleted classroom', async () => {
    await assertSucceeds(tombstone(teacher()));
    await assertFails(join(other(), OTHER, 'Sam'));
  });
});

describe('class details, photos and classmates', () => {
  beforeEach(seedClassroom);

  it('lets the teacher set section, room and theme', async () => {
    await assertSucceeds(updateDoc(doc(teacher(), 'classrooms', CID), { section: 'B.Tech', room: 'Lab 2', theme: 3 }));
  });

  it('refuses a theme outside the built-in artworks', async () => {
    await assertFails(updateDoc(doc(teacher(), 'classrooms', CID), { theme: 99 }));
    await assertFails(updateDoc(doc(teacher(), 'classrooms', CID), { theme: 'x' }));
  });

  it("lets a student join with their own account photo, and refuses someone else's", async () => {
    const db = googleUser(STUDENT, 'Riya', PHOTO);
    const withPhoto = (url) => {
      const batch = writeBatch(db);
      batch.set(doc(db, 'classrooms', CID, 'members', STUDENT), {
        name: 'Riya', devicePublicKey: KEY, joinCode: CODE, joinedAt: serverTimestamp(), photoUrl: url,
      });
      batch.set(doc(db, 'users', STUDENT, 'classrooms', CID), { joinedAt: serverTimestamp() });
      return batch.commit();
    };
    await assertFails(withPhoto('https://evil.example/track.png'));
    await assertSucceeds(withPhoto(PHOTO));
  });

  it('lets classmates read the member list, but not outsiders', async () => {
    await seedMember(STUDENT, 'Riya');
    await seedMember(OTHER, 'Sam');
    await assertSucceeds(getDocs(collection(student(), 'classrooms', CID, 'members')));
    await assertFails(getDocs(collection(googleUser('stranger', 'Zed'), 'classrooms', CID, 'members')));
  });
});

describe('sessions and attendance records', () => {
  const session = () => ({ status: 'ACTIVE', startedAt: new Date(), endsAt: new Date() });

  beforeEach(async () => { await seedClassroom(); await seedMember(); });

  it('lets the teacher start a session that members can see', async () => {
    await assertSucceeds(setDoc(doc(teacher(), 'classrooms', CID, 'sessions', 's1'), session()));
    await assertSucceeds(getDoc(doc(student(), 'classrooms', CID, 'sessions', 's1')));
  });

  it('hides sessions from non-members and refuses students starting one', async () => {
    await seed((db) => setDoc(doc(db, 'classrooms', CID, 'sessions', 's1'), session()));
    await assertFails(getDoc(doc(other(), 'classrooms', CID, 'sessions', 's1')));
    await assertFails(setDoc(doc(student(), 'classrooms', CID, 'sessions', 's2'), session()));
  });

  it('refuses a student marking themselves present', async () => {
    await assertFails(setDoc(doc(student(), 'classrooms', CID, 'sessions', 's1', 'records', STUDENT), { markedAt: new Date() }));
  });

  it("lets the teacher upload records, and a student read only their own", async () => {
    await assertSucceeds(setDoc(doc(teacher(), 'classrooms', CID, 'sessions', 's1', 'records', STUDENT), { markedAt: new Date() }));
    await seed((db) => setDoc(doc(db, 'classrooms', CID, 'sessions', 's1', 'records', OTHER), { markedAt: new Date() }));
    await assertSucceeds(getDoc(doc(student(), 'classrooms', CID, 'sessions', 's1', 'records', STUDENT)));
    await assertFails(getDoc(doc(student(), 'classrooms', CID, 'sessions', 's1', 'records', OTHER)));
  });
});
