# AGENTS

## Design bar

This library is broad enough that cleanliness mistakes turn into correctness mistakes.

When simplifying or extending the API:

1. Prefer fewer representations.
2. Prefer fewer sources of truth.
3. Prefer making illegal states harder to express.
4. Prefer moving policy into one place.
5. Keep raw escape hatches raw unless there is a clear repeated boundary worth naming.

## Do not add wrapper types unless they buy something real

Adding a type is justified only when it does at least one of these:

1. Encodes a real invariant that plain `String`/`JsonObject`/`JsonElement` does not.
2. Collapses multiple overlapping cases into one clearer algebra.
3. Marks a repeated extension boundary that appears often enough to deserve a single explicit seam.
4. Removes duplicated validation or duplicated branching logic.
5. Makes invalid provider or request combinations materially harder to construct.

If a wrapper only renames an existing type without adding an invariant, collapsing states, or simplifying composition, do not add it.

Good wrapper example:
- `value class SomeId(val value: String)` when it prevents mixing unrelated identifiers and gives a real domain boundary
- this is fine because it buys a real invariant and makes invalid states harder to express

Also good:
- a tiny validating type around a constrained string field when the API does not actually accept arbitrary strings
- for example, if a field only accepts a documented finite or pattern-constrained language, prefer a small type with validation over a raw `String`
- this is justified because it moves validation to the boundary and makes the accepted language explicit
- provider-defined identifiers can also be wrapped when that buys real type safety
- for example, `ModelId`, `FileId`, or another domain identifier wrapper is reasonable if it prevents mixing unrelated IDs or clarifies a boundary that otherwise stays stringly
- do not do this mechanically; the wrapper still has to buy an actual domain boundary or invariant

Rejected example:
- cosmetic wrappers like `OpaqueJsonObject` / `OpaqueJsonElement`
- they add ceremony without reducing states, reducing duplication, or improving the model

Accepted example:
- `JsonExtras` for recurring `extraBody` escape hatches
- it marks one repeated extension seam instead of scattering bare `JsonObject?` across every request boundary

## Typed vs raw fields

1. Stable documented fields should be typed when that improves validation and searchability.
2. Provider-specific or unstable fields can remain raw.
3. Do not force unstable provider drift into enums prematurely.
4. Keep the main request algebra clean and push provider quirks behind explicit provider-option boundaries.
5. If a field is not truly free-form, prefer a validating small type over a raw `String`.

## Acyclicity

1. Prefer the codebase to remain acyclic at the compilation-unit level.
2. Prefer definitions and policy flow that are easy to keep acyclic.
3. Centralize provider policy and shared invariants partly because that reduces dependency churn and makes acyclic structure easier to maintain.
4. Do not introduce abstractions that look neat locally but force bidirectional dependencies between files.

## Cleanup priorities

When in doubt, choose work in this order:

1. unify policy
2. remove duplicated logic
3. tighten invariants
4. type stable fields
5. refactor internal plumbing

Do not choose "new wrapper type" as a cleanup move unless it satisfies the rules above.
