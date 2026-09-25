module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  // Restrict Jest's file crawler to code and configured TypeScript tests.  The
  // repository also contains Android builds and release artifacts, neither of
  // which is part of this test suite and both make test discovery needlessly
  // slow.
  roots: ["<rootDir>/src", "<rootDir>/tests"],
  // `npm run build` remains the strict type-check gate.  Tests only need
  // transpilation, avoiding a second full TypeScript program build per run.
  transform: {
    "^.+\\.tsx?$": ["ts-jest"]
  },
  testMatch: ["**/tests/**/*.test.ts"]
};
