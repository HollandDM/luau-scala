# Rust Luau Ecosystem: Technical Reference

## 1. Overview

Four categories of Rust tooling interact with Luau:

| Category | Crates / Projects |
|---|---|
| Lossless parser / AST | `full-moon` v2.2.0 |
| Alternative CST parser | `luau-parser` v0.2.68 |
| C++ VM FFI bindings | `mlua`, `mluau`, `luau-src` (mlua-rs), `luau0-src`, `mlua-sys` |
| Low-level unsafe bindings | `luau-rs` (WIP), `luau-src` (Vurv78) |
| Pure-Rust Lua VMs (adjacent) | `piccolo`, `rilua`, `lust` — Lua, not Luau |
| Official Luau runtimes | Lune (Rust+mlua), Lute (C++) |

No pure-Rust Luau VM exists. All execution paths go through the C++ Luau implementation.

---

## 2. `full-moon` — Lossless Luau Parser

**Source:** https://github.com/Kampfkarren/full-moon  
**Docs:** https://docs.rs/full_moon/latest/full_moon/  
**Version:** 2.2.0 (April 2026), MPL-2.0  
**Dependents:** 254+ crates; used by StyLua, various LSP tools

### 2.1 Design Philosophy

full-moon is a **lossless** parser: parsing followed by printing returns byte-identical source. The project explicitly draws inspiration from:
- LPGhatguy's `mab` — style-preserving Lua parser
- benjamn's `recast` — style-preserving JS transformer

The guarantee: every comment, whitespace character, quote style, and operator spacing survives a round-trip through `parse` → `print`.

### 2.2 Crate Structure

```
full-moon/
  full-moon/src/
    lib.rs           — parse(), parse_fallible()
    tokenizer.rs     — Lexer, Token, TokenReference, TokenType
    ast/
      mod.rs         — Block, Stmt, Expression, all core nodes
      luau.rs        — TypeInfo, TypeDeclaration, etc.
      span.rs        — ContainedSpan
      punctuated.rs  — Punctuated<T>, Pair<T>
      parser_structs.rs — Ast, AstResult
    visitors.rs      — Visitor, VisitorMut traits
    node.rs          — Node trait
  full-moon-derive/  — proc macros for visitor generation
```

### 2.3 Tokenizer

#### `Token`

Private fields, accessed via methods:
- `start_position() -> Position`
- `end_position() -> Position`
- `token_type() -> &TokenType`  (type + data)
- `token_kind() -> TokenKind`   (type only, no data)

#### `TokenType` — all variants

```rust
enum TokenType {
    Eof,
    Identifier    { identifier: ShortString },
    Number        { text: ShortString },   // preserves "0x", "1e3" exactly
    StringLiteral { literal: ShortString,
                    multi_line_depth: usize,
                    quote_type: StringLiteralQuoteType },
    Symbol        { symbol: Symbol },      // keywords + operators
    Whitespace    { characters: ShortString },
    SingleLineComment  { comment: ShortString },
    MultiLineComment   { blocks: usize, comment: ShortString }, // blocks = # of = signs
    Shebang       { line: ShortString },
    // Luau only (feature = "luau"):
    InterpolatedString { literal: ShortString, kind: InterpolatedStringKind },
    // cfxlua only:
    CStyleComment { comment: ShortString },
}
```

`ShortString` is a `smol_str`-backed inline string for small identifiers/keywords, avoiding heap allocation.

`StringLiteralQuoteType`: `Single`, `Double`, `Brackets` (long strings).

`InterpolatedStringKind`: `Begin`, `Middle`, `End`, `Simple` — tracks position within a backtick interpolated string.

#### `TokenReference`

```rust
// private fields:
struct TokenReference {
    leading_trivia:  Vec<Token>,
    token:           Token,
    trailing_trivia: Vec<Token>,
}

impl TokenReference {
    fn leading_trivia(&self)  -> impl Iterator<Item = &Token>;
    fn trailing_trivia(&self) -> impl Iterator<Item = &Token>;
    fn token(&self) -> &Token;
    fn with_token(self, token: Token) -> Self;  // replace token, keep trivia
    fn new(leading: Vec<Token>, token: Token, trailing: Vec<Token>) -> Self;
    fn symbol(symbol: Symbol) -> Self;  // convenience: whitespace-surrounded symbol
    fn is_symbol(&self, symbol: Symbol) -> bool;
}
impl Deref for TokenReference { type Target = Token; }
```

Trivia tokens are `Token` values whose `TokenType` is `Whitespace`, `SingleLineComment`, `MultiLineComment`, or `Shebang`. The leading trivia of a token owns all whitespace/comments between the previous token and this one. Trailing trivia typically holds only the whitespace up to end-of-line; the newline itself may be leading trivia of the next token. (A bug fixed in 1.1.1 involved tab characters being misrouted to leading instead of trailing trivia.)

The `Lexer` struct produces `LexerResult<TokenReference>` and can be driven incrementally.

### 2.4 AST Structure

#### Entry Point

```rust
// lib.rs
fn parse(code: &str) -> Result<Ast, Vec<Error>>;
fn parse_fallible(code: &str, lua_version: LuaVersion) -> AstResult;

struct AstResult {
    // always contains an Ast even if errors present
    // partial ASTs lose some guarantees
}
```

`LuaVersion` is a bitfield-like struct; create with builder methods:
```rust
LuaVersion::new()          // all enabled features
LuaVersion::lua51()        // 5.1 only
LuaVersion::luau()         // Luau only
LuaVersion::lua54().with_luau()  // composable
```
`BitOr` implemented for combining versions. Each method guarded by corresponding Cargo feature flag.

#### `Block`

```rust
struct Block {
    stmts:     Vec<(Stmt, Option<TokenReference>)>,  // (stmt, optional semicolon)
    last_stmt: Option<(LastStmt, Option<TokenReference>)>,
}
```

#### `Stmt` (statement enum)

```rust
enum Stmt {
    Assignment(Assignment),
    Do(Do),
    FunctionCall(FunctionCall),
    FunctionDeclaration(FunctionDeclaration),
    GenericFor(GenericFor),
    If(If),
    LocalAssignment(LocalAssignment),
    LocalFunction(LocalFunction),
    NumericFor(NumericFor),
    Repeat(Repeat),
    While(While),
    // luau feature:
    CompoundAssignment(CompoundAssignment),
    ConstAssignment(ConstAssignment),
    ConstFunction(ConstFunction),
    TypeDeclaration(TypeDeclaration),
    ExportedTypeDeclaration(ExportedTypeDeclaration),
    TypeFunction(TypeFunction),
    ExportedTypeFunction(ExportedTypeFunction),
    // lua52/luajit:
    Goto(Goto),
    Label(Label),
}
```

#### `LastStmt`

```rust
enum LastStmt {
    Break(TokenReference),
    Continue(TokenReference),  // luau only
    Return(Return),
}
```

#### `Expression`

```rust
enum Expression {
    BinaryOperator { lhs: Box<Expression>, binop: BinOp, rhs: Box<Expression> },
    Parentheses    { contained: ContainedSpan, expression: Box<Expression> },
    UnaryOperator  { unop: UnOp, expression: Box<Expression> },
    Function(Box<AnonymousFunction>),
    FunctionCall(FunctionCall),
    TableConstructor(TableConstructor),
    Number(TokenReference),
    String(TokenReference),
    Symbol(TokenReference),
    Var(Var),
    // luau only:
    IfExpression(IfExpression),
    InterpolatedString(InterpolatedString),
    TypeAssertion { expression: Box<Expression>, type_assertion: TypeAssertion },
}
```

`TypeAssertion` holds `assertion_op: TokenReference` (`::`) and `cast_to: TypeInfo`.

#### `FunctionBody`

Luau-augmented form includes type annotation fields:
```rust
struct FunctionBody {
    parameters_parentheses: ContainedSpan,
    parameters: Punctuated<Parameter>,
    // luau only:
    generics: Option<GenericDeclaration>,
    return_type: Option<TypeSpecifier>,
    // ... plus type_specifiers on each parameter
}
```

#### `NumericFor` / `GenericFor` — Luau additions

```rust
struct NumericFor {
    // ... standard fields ...
    type_specifier: Option<TypeSpecifier>,  // luau: type annotation on index var
}

struct GenericFor {
    // ... standard fields ...
    type_specifiers: Vec<Option<TypeSpecifier>>,  // luau: per-variable type annotations
}
```

#### `AnonymousFunction`

```rust
struct AnonymousFunction {
    attributes: Vec<LuauAttribute>,  // luau: @native, @checked
    function_token: TokenReference,
    body: FunctionBody,
}
```

### 2.5 Structural Types

#### `ContainedSpan`

Represents any `(open, close)` delimiter pair:

```rust
struct ContainedSpan {
    pub(crate) tokens: (TokenReference, TokenReference),
}
impl ContainedSpan {
    fn new(start: TokenReference, end: TokenReference) -> Self;
    fn tokens(&self) -> (&TokenReference, &TokenReference);
}
```

Used for: parentheses, brackets `[]`, braces `{}`, angle brackets `<>`, generics `<<>>`.

#### `Punctuated<T>`

Comma-separated (or any separator) sequences:

```rust
struct Punctuated<T> { /* private */ }

enum Pair<T> {
    Punctuated(T, TokenReference),  // item + trailing separator
    End(T),                         // last item, no separator
}
```

Iterators: `iter()` yields `&T`; `pairs()` yields `&Pair<T>` giving access to both items and their separators. This is how `f(a, b, c)` preserves each comma token.

### 2.6 Luau Type System Nodes

All in `full_moon::ast::luau` (feature `luau`).

#### `TypeInfo` — the central type enum

```rust
enum TypeInfo {
    Array    { braces: ContainedSpan, access: Option<TokenReference>, type_info: Box<TypeInfo> },
    Basic(TokenReference),          // e.g. `string`, `number`, custom identifiers
    String(TokenReference),         // singleton string literal type
    Boolean(TokenReference),        // singleton boolean
    Callback { generics: Option<GenericDeclaration>,
               parentheses: ContainedSpan,
               arguments: Punctuated<TypeArgument>,
               arrow: TokenReference,
               return_type: Box<TypeInfo> },
    Generic  { base: TokenReference, arrows: ContainedSpan,
               generics: Punctuated<TypeInfo> },
    GenericPack { name: TokenReference, ellipsis: TokenReference },
    Intersection { types: TypeIntersection },
    Module   { module: TokenReference, punctuation: TokenReference, type_info: Box<IndexedTypeInfo> },
    Optional { base: Box<TypeInfo>, question_mark: TokenReference },
    Table    { braces: ContainedSpan, fields: Punctuated<TypeField> },
    Typeof   { typeof_token: TokenReference, parentheses: ContainedSpan, inner: Box<Expression> },
    Tuple    { parentheses: ContainedSpan, types: Punctuated<TypeInfo> },
    Union    { types: TypeUnion },
    Variadic { ellipsis: TokenReference, type_info: Box<TypeInfo> },
    VariadicPack { ellipsis: TokenReference, name: TokenReference },
}
```

#### Union / Intersection

```rust
struct TypeUnion        { leading: Option<TokenReference>, types: Punctuated<TypeInfo> }
struct TypeIntersection { leading: Option<TokenReference>, types: Punctuated<TypeInfo> }
```

`leading` carries the optional leading `|` or `&` before the first element (Luau allows them).

#### Table Types

```rust
struct TypeField {
    access: Option<TokenReference>,  // `read` modifier
    key: TypeFieldKey,
    colon: TokenReference,
    value: TypeInfo,
}

enum TypeFieldKey {
    Name(TokenReference),
    IndexSignature { brackets: ContainedSpan, inner: TypeInfo },
}
```

#### Type Declarations

```rust
struct TypeDeclaration {
    type_token:  TokenReference,
    base:        TokenReference,   // the alias name
    generics:    Option<GenericDeclaration>,
    equal_token: TokenReference,
    declare_as:  TypeInfo,
}

struct ExportedTypeDeclaration {
    export_token:     TokenReference,
    type_declaration: TypeDeclaration,
}

struct TypeFunction {
    type_token:    TokenReference,
    function_token: TokenReference,
    function_name:  TokenReference,
    function_body:  FunctionBody,
}
```

#### Generic Declarations

```rust
struct GenericDeclaration {
    arrows:   ContainedSpan,
    generics: Punctuated<GenericDeclarationParameter>,
}

struct GenericDeclarationParameter {
    parameter: GenericParameterInfo,
    default:   Option<(TokenReference, TypeInfo)>,  // `= DefaultType`
}

enum GenericParameterInfo {
    Name(TokenReference),
    Variadic { name: TokenReference, ellipsis: TokenReference },
}
```

#### Inline Annotations

```rust
struct TypeSpecifier {
    punctuation: TokenReference,  // the `:` colon
    type_info:   TypeInfo,
}

struct TypeArgument {
    name: Option<(TokenReference, TokenReference)>,  // `name:` in callback params
    type_info: TypeInfo,
}

struct TypeAssertion {
    assertion_op: TokenReference,  // `::`
    cast_to:      TypeInfo,
}
```

#### Type Instantiation (added 2.1.0)

```rust
// As Suffix and on MethodCall:
// f<<T, U>>  — double-angle type instantiation
```

#### Const Assignment (added 2.2.0)

```rust
struct ConstAssignment {
    const_token:    TokenReference,
    type_specifiers: Vec<Option<TypeSpecifier>>,
    name_list:      Punctuated<TokenReference>,
    equal_token:    Option<TokenReference>,
    expr_list:      Punctuated<Expression>,
}
```

### 2.7 Visitor Pattern

Generated by proc-macro in `full-moon-derive`. Two traits:

```rust
trait Visitor {
    fn visit_ast(&mut self, ast: &Ast) { /* default: traverse */ }
    fn visit_block(&mut self, block: &Block) {}
    fn visit_block_end(&mut self, block: &Block) {}
    // ... ~139 methods total, each with *_end counterpart
}

trait VisitorMut {
    fn visit_ast(&mut self, ast: Ast) -> Ast { ast }
    fn visit_block(&mut self, block: Block) -> Block { block }
    // ... same set, owned values, default is identity
}
```

Traversal is driven by `Visit` / `VisitorMut` sealed traits implemented on every AST node. Dispatch is structural: `Vec<T>` iterates items, `Option<T>` conditionally visits, `Box<T>` dereferences, tuples visit both components.

Key visit method groups:
- Core statements: `visit_assignment`, `visit_local_assignment`, `visit_function_declaration`, `visit_if`, `visit_while`, `visit_repeat`, `visit_numeric_for`, `visit_generic_for`
- Expressions: `visit_expression`, `visit_function_call`, `visit_method_call`, `visit_table_constructor`
- Tokens: `visit_token`, `visit_token_reference`, `visit_identifier`, `visit_number`, `visit_string_literal`, `visit_symbol`, `visit_whitespace`, `visit_single_line_comment`, `visit_multi_line_comment`
- Luau types: `visit_type_info`, `visit_type_declaration`, `visit_type_specifier`, `visit_type_assertion`, `visit_type_union`, `visit_type_intersection`, `visit_type_field`, `visit_generic_declaration`, `visit_indexed_type_info`, `visit_type_instantiation`
- Luau statements: `visit_const_assignment`, `visit_const_function`, `visit_compound_assignment`, `visit_if_expression`, `visit_interpolated_string`, `visit_luau_attribute`, `visit_exported_type_declaration`, `visit_exported_type_function`
- Lua 5.2: `visit_goto`, `visit_label`
- Lua 5.4: `visit_attribute`

### 2.8 Node Trait

Sealed, implemented on all AST nodes:

```rust
trait Node {
    fn start_position(&self) -> Option<Position>;
    fn end_position(&self)   -> Option<Position>;
    fn similar(&self, other: &Self) -> bool where Self: Sized;  // semantic equality ignoring position
    fn tokens(&self) -> Tokens<'_>;
    // provided:
    fn range(&self) -> Option<(Position, Position)>;
    fn surrounding_trivia(&self) -> (Vec<&Token>, Vec<&Token>);
}
```

`surrounding_trivia` gives access to comments/whitespace outside the node's own token references — useful for extracting doc comments before a function.

### 2.9 Feature Flags

```toml
[features]
default = ["lua52", "lua53", "lua54", "luau"]
lua52   = []
lua53   = ["lua52"]
lua54   = ["lua53"]
luau    = []
luajit  = ["lua52"]
cfxlua  = ["lua54"]
roblox  = ["luau"]   # alias
serde   = ["dep:serde"]
```

Dialect conflicts: `::label::` (goto labels, Lua 5.2) vs. `::` (type assertion operator, Luau). The parser resolves by version context passed to `parse_fallible`.

---

## 3. `luau-parser` — Alternative CST

**Source:** https://lib.rs/crates/luau-parser  
**Version:** 0.2.68 (May 2025), Rust 2024 edition

A separate crate (not related to full-moon) producing a CST with error recovery. Key differences from full-moon:

| Aspect | full-moon | luau-parser |
|---|---|---|
| Root node | `Ast` | `Cst` |
| Error handling | `parse_fallible` returns `AstResult` | Non-terminating, inserts `*error*` placeholder tokens |
| Type annotation coverage | Complete via `TypeInfo` enum | `TypeDefinition`, `TypeFunction`, `TypeValue` |
| Print roundtrip | `full_moon::print(ast)` | `Cst::print()` |
| Line count | ~11K SLoC | ~4K SLoC |
| Monthly downloads | 76K | ~1.2K |

Traits: `Parse`, `TryParse`, `ParseWithArgs`, `TryParseWithArgs`, `Print`, `GetRange`.

---

## 4. C++ Luau VM Bindings

### 4.1 Crate Dependency Graph

```
mlua (high-level Rust API)
  └── mlua-sys (FFI bindings)
        └── luau-src (bundles Luau C++ sources, builds via cc crate)
              └── Luau/ (git submodule of luau-lang/luau)
```

Separately:
```
luau0-src  (independent bundle, used by ulua-sys)
  └── Luau C++ sources
```

### 4.2 `luau-src` (mlua-rs/luau-src-rs)

**Source:** https://github.com/mlua-rs/luau-src-rs  
Language: 95.9% C++, 3.6% C, 0.5% Rust

Contains the Luau C++ implementation as a git submodule under `luau/`. The Rust `build.rs` uses the `cc` crate to compile Luau's C++ source files into a static library that `mlua-sys` links against. No user code needed — `mlua` with `luau` feature auto-invokes this.

Current version tracks Luau ~700+.

### 4.3 `luau0-src`

**Source:** https://crates.io/crates/luau0-src  
**Version:** 0.17.1+luau702

Independent alternative to mlua-rs's luau-src. Used by `ulua`/`ulua-sys`. Also compiles Luau C++ from bundled sources. The `+luau702` version suffix indicates which upstream Luau release is bundled.

### 4.4 `mlua`

**Source:** https://github.com/mlua-rs/mlua  
**Docs:** https://docs.rs/mlua  
**Version:** 0.11.6+

High-level safe Rust bindings. Feature flags:

```toml
[features]
luau        # auto-vendored, no separate vendored flag needed
luau-jit    # JIT backend
luau-vector4 # 4D vectors
```

Luau-specific `Lua` methods:
- `lua.sandbox()` — enables sandbox mode (read-only stdlib metatables, restricted `collectgarbage`)
- `lua.set_compiler(options)` — sets global Luau compiler options for all `load()` calls
- `lua.enable_jit(on)` — toggles JIT for subsequently loaded chunks
- `lua.set_memory_category(name)` — memory tracking categories
- `lua.heap_dump()` — heap snapshot
- `lua.set_interrupt(fn)` — periodic yield/interrupt hook
- `lua.create_require_function(resolver)` — custom `require()` implementation
- `lua.create_buffer(size)` — Luau buffer objects

### 4.5 `mluau` (fork of mlua)

**Source:** https://github.com/mluau/mluau

Luau-focused fork with divergences:
- Luau continuations: Rust functions yield back to Luau, resumed thread calls Rust continuation
- Removed: async/await (too prone to deadlocks in Luau context), `Lua::scope`
- Namecall optimization: userdata methods use `__namecall` metamethod for faster dispatch
- `Lua::create_dynamic_userdata` for runtime-typed userdata
- `Thread::close`, `Thread::pop_results`, `Lua::traceback`
- Removed `__gc` on userdata per Luau spec

### 4.6 `luau-rs` (LoganDark)

**Source:** https://github.com/LoganDark/luau-rs  
**Status:** WIP proof-of-concept, no published releases

Two-layer architecture:
- `luau-sys` — translates Luau's C++ API to pure C types (no C++ in Rust FFI)
- `luau` — safe Rust API over `luau-sys`

Aspirational features: type analysis, parallel Luau (multi-threaded VM), thread-specific security identities, async/await. In practice, "almost none of the features listed above are actually functional." Not suitable for production use.

---

## 5. Standalone Luau Runtimes

### 5.1 Lune

**Source:** https://github.com/lune-org/lune  
Language: 71.7% Rust, 27.9% Luau  
**Status:** Active, production-quality

Lune is a standalone Luau runtime built in Rust using `mlua` for C++ Luau VM execution. Provides async-first standard library (filesystem, networking, processes, sockets). Architecture: `/crates/` monorepo. Analogous to Node.js/Deno for Luau. Does not implement its own VM — delegates entirely to mlua/luau-src-rs.

### 5.2 Lute (Official)

**Source:** https://github.com/luau-lang/lute  
Language: 48.6% C++, 48.0% Luau, 2.7% CMake  
**Status:** Active, from Luau team

Official first-party standalone runtime. Written in C++ because Luau VM is C++. Includes a std library written in Luau code itself. Has a custom build tool (`luthier`) written in Luau. Exposes AST manipulation APIs for writing code transformations in Luau. Not Rust.

### 5.3 Pure-Rust Lua VMs (Lua, not Luau)

- **piccolo** (https://github.com/kyren/piccolo): stackless Lua VM in pure Rust, sandboxing focus
- **rilua** (https://github.com/wowemulation-dev/rilua): Lua 5.1.1 port, zero `unsafe`, no C toolchain
- **lust**: minimal Lua subset VM in Rust

None implement Luau type system or Luau bytecode. Not usable for Luau execution.

---

## 6. Lessons for a Luau Lexer/Parser in Scala

### 6.1 Trivia Architecture

The core insight from full-moon: **trivia belongs on tokens, not between nodes**. Specifically:

- Every `TokenReference` owns `Vec<Token>` for leading and trailing trivia
- Trivia tokens use the same `TokenType` as main tokens (`Whitespace`, `SingleLineComment`, `MultiLineComment`)
- Leading trivia = whitespace/comments between previous non-trivia token and this token
- Trailing trivia = whitespace up to (not including) the newline; newline attaches as leading trivia of next token

In Scala, model this as:
```scala
case class TokenRef(
  leadingTrivia: Vector[TriviaToken],
  token: Token,
  trailingTrivia: Vector[TriviaToken]
)
```

This is the same approach used by Roslyn (C#), rust-analyzer's rowan crate, and Swift's libSyntax.

### 6.2 Punctuated Sequences

Comma-separated lists must preserve comma tokens for lossless round-trip. full-moon's `Punctuated<T>` with `Pair<T>` is the right abstraction:

```scala
enum Pair[+A]:
  case Punctuated(item: A, sep: TokenRef)
  case End(item: A)

case class Punctuated[A](pairs: Vector[Pair[A]])
```

Avoids the anti-pattern of `List[A]` with a separate `List[TokenRef]` that gets out of sync.

### 6.3 `ContainedSpan` for Delimiters

Any `(`, `)` / `[`, `]` / `{`, `}` / `<`, `>` pair should be modeled as a contained span to keep both tokens:

```scala
case class ContainedSpan(open: TokenRef, close: TokenRef)
```

This prevents the common bug of losing the closing token during transformation.

### 6.4 Luau Grammar Ambiguities

Key conflict: `::` means both goto label delimiter (Lua 5.2) and type assertion operator (Luau). full-moon resolves with explicit `LuaVersion` at parse time. In Scala: pass a `DialectConfig` through the parser rather than relying on static configuration.

Other Luau-specific lexer complexities:
- Interpolated strings: backtick `` ` `` starts a string, `{` opens an expression hole, `}` resumes string, another `` ` `` ends — requires a lexer stack/state machine. `InterpolatedStringKind` (`Begin`/`Middle`/`End`/`Simple`) tracks this.
- Long strings: `[==[...]==]` where `blocks` = count of `=` signs must match exactly
- Type annotations: `:` in function parameters is type annotation, not method call separator — context-sensitive
- `typeof(expr)` in type position: lexer produces `Identifier("typeof")` but parser treats it specially

### 6.5 TypeInfo Recursive Structure

`TypeInfo` is deeply recursive (union of optionals of callbacks returning intersections). Ensure the Scala ADT uses `lazy val` or wraps in a by-name reference to avoid infinite struct sizes:

```scala
enum TypeInfo:
  case Optional(base: TypeInfo, questionMark: TokenRef)  // needs box equivalent
  case Callback(args: Punctuated[TypeArgument], arrow: TokenRef, ret: TypeInfo)
  case Union(types: TypeUnion)
  // etc.
```

In Scala 3, `enum` cases with recursive fields work fine since case classes are reference types. No explicit boxing needed unlike Rust.

### 6.6 Visitor Pattern

full-moon generates ~139 visitor methods via proc-macro. In Scala, use:
1. Pattern matching directly on sealed hierarchies (simplest for transforms)
2. Cats `Traverse` typeclass for structural traversal
3. A hand-written `Visitor` trait with default no-op methods (mirrors full-moon design)

The `_end` suffix pattern (enter + exit hooks) is valuable for stateful visitors like scope analysis.

### 6.7 Feature Flag Equivalents

full-moon gates Luau nodes behind Cargo feature flags. In Scala/JVM, handle dialect at runtime with a `DialectConfig` case class rather than conditional compilation. This simplifies distribution but requires all dialect branches to compile.

### 6.8 Error Recovery

`parse_fallible` always returns an AST. The `luau-parser` crate goes further with placeholder injection. For a tooling-oriented parser (LSP, formatter), error recovery is mandatory. Consider Parsley's (Scala parser combinator library) `attempt`/`recover` combinators, or a hand-written recursive descent parser with explicit error nodes.

### 6.9 Position Tracking

full-moon's `Position` tracks byte offset, line, and column. `Node::surrounding_trivia` lets you retrieve doc comments attached to a node. Preserve byte offsets (not just line/col) for accurate LSP range reporting.

---

## 7. Quick Reference: Key Types

| Type | Module | Purpose |
|---|---|---|
| `Ast` | `ast` | Root parse result |
| `AstResult` | `ast` | Fallible parse; always has Ast |
| `Block` | `ast` | Statement sequence |
| `Stmt` | `ast` | Statement variants |
| `Expression` | `ast` | Expression variants |
| `Token` | `tokenizer` | Position + TokenType |
| `TokenReference` | `tokenizer` | Token + leading/trailing trivia |
| `TokenType` | `tokenizer` | All token variants including trivia |
| `ContainedSpan` | `ast::span` | Open/close delimiter pair |
| `Punctuated<T>` | `ast::punctuated` | Separator-interleaved sequence |
| `Pair<T>` | `ast::punctuated` | Item + optional trailing separator |
| `TypeInfo` | `ast::luau` | All Luau type annotation variants |
| `TypeSpecifier` | `ast::luau` | `: TypeInfo` inline annotation |
| `TypeDeclaration` | `ast::luau` | `type X = ...` statement |
| `GenericDeclaration` | `ast::luau` | `<T, U>` or `<T = Default>` |
| `LuaVersion` | `ast` | Dialect bitfield for parse configuration |
| `Visitor` | `visitors` | Immutable AST traversal trait |
| `VisitorMut` | `visitors` | Mutable/transforming traversal trait |
| `Node` | `node` | Position + token access for all nodes |

---

## Sources

- [full-moon GitHub](https://github.com/Kampfkarren/full-moon)
- [full_moon docs.rs](https://docs.rs/full_moon/latest/full_moon/)
- [full_moon::ast docs](https://docs.rs/full_moon/latest/full_moon/ast/index.html)
- [full_moon::tokenizer::TokenType](https://docs.rs/full_moon/latest/full_moon/tokenizer/enum.TokenType.html)
- [full_moon::visitors::Visitor](https://docs.rs/full_moon/latest/full_moon/visitors/trait.Visitor.html)
- [full_moon::node::Node](https://docs.rs/full_moon/latest/full_moon/node/trait.Node.html)
- [full_moon Lua version guide](https://mintlify.wiki/Kampfkarren/full-moon/guides/lua-versions)
- [full-moon CHANGELOG](https://github.com/Kampfkarren/full-moon/blob/main/CHANGELOG.md)
- [lib.rs full_moon](https://lib.rs/crates/full_moon)
- [mlua GitHub](https://github.com/mlua-rs/mlua)
- [mlua docs.rs Lua struct](https://docs.rs/mlua/latest/mlua/struct.Lua.html)
- [luau-src-rs GitHub](https://github.com/mlua-rs/luau-src-rs)
- [luau0-src crates.io](https://crates.io/crates/luau0-src)
- [mluau GitHub](https://github.com/mluau/mluau)
- [luau-rs GitHub (LoganDark)](https://github.com/LoganDark/luau-rs)
- [luau-parser lib.rs](https://lib.rs/crates/luau-parser)
- [luau-parser types docs](https://docs.rs/luau-parser/latest/luau_parser/types/index.html)
- [Lune GitHub](https://github.com/lune-org/lune)
- [Lute GitHub](https://github.com/luau-lang/lute)
- [piccolo GitHub](https://github.com/kyren/piccolo)
- [StyLua GitHub](https://github.com/JohnnyMorganz/StyLua)
