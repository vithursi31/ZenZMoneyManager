# Role-Based API Access Control — Implementation Guide

Quick reference for adding role-restricted endpoints in MindMap AI (svcs/core).

## 1. User Roles

Defined in [UserRole.java](svcs/core/src/main/java/app/mindmapai/domain/type/UserRole.java):

| Role | Purpose |
|---|---|
| `ROLE_ANONYMOUS` | Public / unauthenticated users |
| `ROLE_SUBSCRIBER` | Paid subscribers (default for new users) |
| `ROLE_SUPPORT` | Support team |
| `ROLE_MARKETING` | Marketing team |
| `ROLE_GALLERY` | Gallery content editors |
| `ROLE_DEVELOPMENT` | Dev team |
| `ROLE_INTEGRATION` | Service-to-service (granted by `ServiceTokenFilter`, not stored on User) |

Roles are stored on the `User` entity as `Set<UserRole> roles` ([User.java:43](svcs/core/src/main/java/app/mindmapai/domain/entity/User.java#L43)). New users get `ROLE_SUBSCRIBER` assigned in `RegistrationService`, `GoogleLoginService`, `AppleLoginService`, and `SupportService`.

## 2. How Auth Wires Up Roles

Configured in [CoreBoot.java:55-158](svcs/core/src/main/java/app/mindmapai/CoreBoot.java#L55-L158).

Two filters run before Spring Security:
1. **`ServiceTokenFilter`** — validates `X-Integration-Token` for `/int/**`; grants `ROLE_INTEGRATION`.
2. **`JwtAuthenticationFilter`** — extracts JWT from `Authorization: Bearer <token>` header or `?authorization=` query param; loads `User` and sets authorities from `user.roles`.

Anonymous (no token / no session) requests get `ROLE_ANONYMOUS` automatically.

Session-based browser requests use `JSESSIONID`; authorities come from the same `User.roles` set via Spring Security's session authentication.

## 3. Restricting an Endpoint — `@RolesAllowed`

Use the `javax.annotation.security.@RolesAllowed` annotation. **Pass the role name WITHOUT the `ROLE_` prefix** — Spring strips it.

```java
import javax.annotation.security.RolesAllowed;

@PostMapping(value = "/credits/balance")
@RolesAllowed("SUBSCRIBER")
public ResponseEntity<Result<BigDecimal>> creditBalance(HttpServletRequest request) { ... }
```

Multiple roles (OR):
```java
@RolesAllowed({"SUBSCRIBER", "SUPPORT"})
```

Real examples:
- [DownloadController.java:37](svcs/core/src/main/java/app/mindmapai/web/controller/DownloadController.java#L37) — `@RolesAllowed("SUBSCRIBER")`
- [GalleryController.java:214](svcs/core/src/main/java/app/mindmapai/web/controller/GalleryController.java#L214) — `@RolesAllowed("GALLERY")`
- [CreditBalanceController.java:35](svcs/core/src/main/java/app/mindmapai/web/controller/CreditBalanceController.java#L35) — `@RolesAllowed("SUBSCRIBER")`

A request that fails the role check returns **HTTP 403**.

## 4. Public Endpoints (No Auth Required)

There is **no `@PermitAll` annotation** — public paths are declared at the security config level. Two layers must allow the path:

### 4a. Spring Security URL patterns — [CoreBoot.java:107-146](svcs/core/src/main/java/app/mindmapai/CoreBoot.java#L107-L146)

Add the URL pattern to the `.antMatchers(...).permitAll()` chain in `CoreBoot.configure(HttpSecurity)`:

```java
.antMatchers(
    "/",
    "/login/**",
    "/register/**",
    "/gpt/**",
    "/preview/**",
    "/mind-map/**",
    "/org-chart/**",
    "/your-new-public-path/**"   // <-- add here
).permitAll()
```

Public API paths live in a separate group around [CoreBoot.java:139-146](svcs/core/src/main/java/app/mindmapai/CoreBoot.java#L139-L146):
```java
.antMatchers(
    "/api/v1/register/**",
    "/api/v1/authenticate/**",
    "/api/v1/refresh-token",
    "/api/v1/reset-password",
    "/api/v1/service-status",
    "/api/v1/mobile/app-config"
).permitAll()
```

Static assets (`/static/**`) are permitted at [CoreBoot.java:128](svcs/core/src/main/java/app/mindmapai/CoreBoot.java#L128).

### 4b. JWT filter skip list — [JwtAuthenticationFilter.java:34-41](svcs/core/src/main/java/app/mindmapai/web/filter/JwtAuthenticationFilter.java#L34-L41)

If the endpoint is under `/api/**`, also add it to the filter's skip list so it doesn't try to validate a missing JWT:

```java
private static final List<String> PUBLIC_PATHS = List.of(
    "/api/v1/register",
    "/api/v1/authenticate",
    "/api/v1/refresh-token",
    "/api/v1/reset-password",
    "/api/v1/service-status",
    "/api/v1/mobile/app-config"
    // add your public API path here
);
```

The copilot WebSocket (`/api/v1/copilot`, `/api/v2/copilot`) is a special case — JWT is optional; anonymous users get a synthetic `UserDetailsImpl("anonymous", ...)` ([JwtAuthenticationFilter.java:73-87](svcs/core/src/main/java/app/mindmapai/web/filter/JwtAuthenticationFilter.java#L73-L87)).

### 4c. Controller — no `@RolesAllowed`

Simply omit `@RolesAllowed`. The endpoint will accept both anonymous and authenticated callers. Real examples:
- [CustomGptController.java:30](svcs/core/src/main/java/app/mindmapai/web/controller/CustomGptController.java#L30) — `POST /gpt/submit`, fully public.
- `GET /api/v1/service-status` — health check, returns `"OK"`.

## 5. Checking "Is the User Authenticated?" From `Context`

`Context.user()` returns a **`String` user ID**. For anonymous callers it returns the literal string `"anonymous"` — **never `null`**. This is set inside [ContextService.java:86](svcs/core/src/main/java/app/mindmapai/service/core/ContextService.java#L86).

### Helper pattern

```java
private static boolean isAuthenticated(Context ctx) {
    String userId = ctx.user();
    return userId != null && !userId.equals("anonymous");
}
```

Or, equivalently, via roles:
```java
boolean isAnonymous = ctx.permissions().contains(UserRole.ROLE_ANONYMOUS.name());
```

### Use in a service / controller

```java
try (Context ctx = contextService.create(cid, sc, Channel.web_app)) {
    if ("anonymous".equals(ctx.user())) {
        // unauthenticated path — return public response or limited data
        return ResponseEntity.ok(Result.of(publicData));
    }
    // authenticated path — full access
    return ResponseEntity.ok(Result.of(myService.loadForUser(ctx)));
}
```

### Use in a page controller (Thymeleaf)

Page controllers often branch on auth state to render different views. Pattern from [HomeController.java:201-233](svcs/core/src/main/java/app/mindmapai/web/controller/HomeController.java#L201-L233):

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (!authentication.getName().equals("anonymous")) {
    // logged in — redirect to /canvas or show personalized content
    return "redirect:/canvas";
} else {
    // anonymous — show landing page
    return "home";
}
```

Inside `ContextService.doCreateContext()` ([ContextService.java:104-116](svcs/core/src/main/java/app/mindmapai/service/core/ContextService.java#L104-L116)) the same check is used to decide whether to load user-specific locale and preferences.

## 6. Fine-Grained Checks in Services — `context.permissions()`

For conditional logic (e.g. "show extra data if user has GALLERY role"), read from the `Context`:

```java
// ContextService populates this from the SecurityContext authorities
boolean hasGalleryRole = context.permissions().contains(UserRole.ROLE_GALLERY.name());
```

Source: [ContextService.java:80-98](svcs/core/src/main/java/app/mindmapai/service/core/ContextService.java#L80-L98). Real usage at [GalleryDataService.java:173](svcs/core/src/main/java/app/mindmapai/service/gallery/GalleryDataService.java#L173).

Note: `context.permissions()` contains the **full role name with `ROLE_` prefix** (e.g. `"ROLE_GALLERY"`), unlike `@RolesAllowed`.

## 7. Boilerplate — New Role-Restricted Endpoint

```java
@RestController
@RequestMapping("/api/v1")
public class MyController {

    private final ContextService contextService;
    private final MyService myService;

    public MyController(ContextService contextService, MyService myService) {
        this.contextService = contextService;
        this.myService = myService;
    }

    @PostMapping("/my-endpoint")
    @RolesAllowed("SUBSCRIBER")
    public ResponseEntity<Result<MyResponse>> myEndpoint(
            @RequestBody MyRequest req,
            HttpServletRequest request) {

        String cid = request.getHeader("X-Correlation-ID");
        SecurityContext sc = SecurityContextHolder.getContext();

        try (Context ctx = contextService.create(cid, sc, Channel.web_app)) {
            Result<MyResponse> result = TxExecutor.execute(ctx,
                () -> myService.doWork(ctx, req));
            return ResponseEntity.ok(result);
        }
    }
}
```

## 8. Adding a New Role

1. Add the enum value to [UserRole.java](svcs/core/src/main/java/app/mindmapai/domain/type/UserRole.java) (always prefixed `ROLE_`).
2. Decide how it gets assigned — manual DB update, support tool, or a new registration path. The `User.roles` set is persisted as part of the user entity.
3. Use `@RolesAllowed("MY_ROLE")` on the endpoints that require it (no `ROLE_` prefix in the annotation).
4. If the role should bypass certain public-path rules, also update the security chain in [CoreBoot.java](svcs/core/src/main/java/app/mindmapai/CoreBoot.java).

## 9. Public vs Protected URL Patterns — Summary

URL-level rules in `CoreBoot.configure(HttpSecurity)` permit-all for: registration, login, password reset, public canvas/gallery pages, webhooks, static assets, health checks. Everything else requires authentication ([CoreBoot.java:148](svcs/core/src/main/java/app/mindmapai/CoreBoot.java#L148)).

If a controller method has no `@RolesAllowed`, any authenticated user (including `ROLE_ANONYMOUS`) can hit it as long as the URL pattern doesn't block them. Annotate every protected endpoint explicitly.

## 10. Checklist Before Shipping

**Role-restricted endpoint:**
- [ ] `@RolesAllowed("...")` on the controller method (no `ROLE_` prefix).
- [ ] If logic varies by role inside the service, use `context.permissions().contains(UserRole.ROLE_X.name())` (with prefix).
- [ ] Verified the endpoint URL isn't permitted-all in `CoreBoot.configure()`.
- [ ] Tested with a valid JWT and an unauthenticated request — expect 200 and 403/401 respectively.

**Public endpoint:**
- [ ] Added URL pattern to the `.permitAll()` chain in [CoreBoot.java](svcs/core/src/main/java/app/mindmapai/CoreBoot.java).
- [ ] If under `/api/**`, added the path to `PUBLIC_PATHS` in [JwtAuthenticationFilter.java](svcs/core/src/main/java/app/mindmapai/web/filter/JwtAuthenticationFilter.java).
- [ ] No `@RolesAllowed` on the method.
- [ ] If behaviour differs for logged-in users, branch on `ctx.user().equals("anonymous")` rather than null-check.
