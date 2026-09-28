package com.finnsbali.api;

import com.finnsbali.BusinessException;
import com.finnsbali.model.Staff;
import com.finnsbali.model.Staff.Role;
import com.finnsbali.model.StaffEvent;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.finnsbali.ErrorCode.EMAIL_TAKEN;
import static com.finnsbali.ErrorCode.FORBIDDEN;
import static com.finnsbali.ErrorCode.INVALID;
import static com.finnsbali.ErrorCode.LAST_ADMIN;
import static com.finnsbali.ErrorCode.MALFORMED;
import static com.finnsbali.ErrorCode.NOT_FOUND;
import static com.finnsbali.ErrorCode.PRECONDITION_REQUIRED;
import static com.finnsbali.ErrorCode.SELF_CHANGE;
import static com.finnsbali.ErrorCode.STALE;
import static com.finnsbali.model.StaffEvent.Action.CREATED;
import static com.finnsbali.model.StaffEvent.Action.DELETED;
import static com.finnsbali.model.StaffEvent.Action.UNLINKED;
import static com.finnsbali.model.StaffEvent.Action.UPDATED;
import static jakarta.ws.rs.core.HttpHeaders.IF_MATCH;

/**
 * Manages who can use the Admin Console. Every call needs the acting staff member to be an active
 * ADMIN, checked here and not only in the Worker.
 * <p>
 * Mutations take {@link Staff#lockAccessChanges} before reading anything, so the actor check and
 * the invariants see every earlier change: nobody changes their own access, and at least one
 * active ADMIN remains. Writes need {@code If-Match} with the version from the {@code ETag}.
 */
@Path(AdminApiFilter.PREFIX + "/staff")
public class StaffApi {
	private static final Logger logger = Logger.getLogger(StaffApi.class);

	static final int MAX_PAGE_SIZE = 200;

	static final int MAX_EVENTS = 100;

	@HeaderParam(AdminApiFilter.ACTOR_SUB_HEADER)
	String actorSub;

	@RegisterForReflection
	public record View(long id, String email, String displayName, Role role, boolean active, boolean linked,
			Instant lastSignInAt, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy,
			int version) {
		static View of(Staff s) {
			return new View(s.id, s.email, s.displayName, s.role, s.active, s.jumpcloudSub != null,
					s.lastSignInAt, s.createdAt, s.createdBy, s.updatedAt, s.updatedBy, s.version);
		}
	}

	/** Strings rather than enums, so bad values become field errors instead of a parse failure. */
	@RegisterForReflection
	public record Input(String email, String displayName, String role, Boolean active) {
	}

	@RegisterForReflection
	public record Page(List<View> items, long total, int page, int size) {
	}

	@RegisterForReflection
	public record Event(StaffEvent.Action action, Map<String, Object> changes, String actorEmail, Instant at) {
	}

	/** {@link Input} after validation. */
	private record Valid(String email, String displayName, Role role, boolean active) {
	}

	@GET
	public Page list(@RestQuery String q, @RestQuery String role, @RestQuery String active,
			@RestQuery Integer page, @RestQuery Integer size) {
		requireAdmin();
		int p = page == null ? 1 : Math.max(1, page);
		int n = size == null ? 50 : Math.clamp(size, 1, MAX_PAGE_SIZE);

		StringBuilder where = new StringBuilder("1 = 1");
		Parameters params = new Parameters();
		if (q != null && !q.isBlank()) {
			where.append(" and (email like :q escape '!' or lower(displayName) like :q escape '!')");
			params.and("q", "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%");
		}
		if (role != null && !role.isBlank()) {
			where.append(" and role = :role");
			params.and("role", Role.parse(role).orElseThrow(() -> new BusinessException(MALFORMED)));
		}
		if (active != null && !active.isBlank()) {
			where.append(" and active = :active");
			params.and("active", parseBoolean(active));
		}

		var query = Staff.<Staff>find(where.toString(), Sort.by("email"), params);
		List<View> items = query.page(p - 1, n).list().stream().map(View::of).toList();
		return new Page(items, query.count(), p, n);
	}

	@GET
	@Path("{id}")
	public Response get(@RestPath long id) {
		requireAdmin();
		return ok(find(id));
	}

	@GET
	@Path("{id}/events")
	public List<Event> events(@RestPath long id, @RestQuery Integer limit) {
		requireAdmin();
		find(id);
		int n = limit == null ? 20 : Math.clamp(limit, 1, MAX_EVENTS);
		return StaffEvent.latestFor(id, n).stream()
				.map(e -> new Event(e.action, e.changes, e.actorEmail, e.at))
				.toList();
	}

	@POST
	@Transactional
	public Response create(Input input) {
		Staff.lockAccessChanges();
		Staff actor = requireAdmin();
		Valid v = validate(input);
		if (Staff.findByEmail(v.email).isPresent()) throw new BusinessException(EMAIL_TAKEN);

		Staff staff = new Staff();
		staff.email = v.email;
		staff.displayName = v.displayName;
		staff.role = v.role;
		staff.active = v.active;
		staff.createdBy = actor.email;
		staff.updatedBy = actor.email;
		staff.persistAndFlush();
		StaffEvent.record(staff, CREATED, StaffEvent.snapshot(staff), actor);
		logger.infof("staff.created staffId=%d actorId=%d", staff.id, actor.id);

		return Response.created(URI.create(AdminApiFilter.PREFIX + "/staff/" + staff.id))
				.entity(View.of(staff))
				.tag(etag(staff))
				.build();
	}

	@PUT
	@Path("{id}")
	@Transactional
	public Response update(@RestPath long id, @HeaderParam(IF_MATCH) String ifMatch, Input input) {
		Staff.lockAccessChanges();
		Staff actor = requireAdmin();
		Staff staff = find(id);
		requireVersion(ifMatch, staff);
		Valid v = validate(input);

		boolean accessChanged = v.role != staff.role || v.active != staff.active;
		if (accessChanged && isSelf(staff, actor)) throw new BusinessException(SELF_CHANGE);
		if (!v.email.equals(staff.email) && Staff.findByEmail(v.email).isPresent()) {
			throw new BusinessException(EMAIL_TAKEN);
		}

		Map<String, Object> changes = new LinkedHashMap<>();
		diff(changes, "email", staff.email, v.email);
		diff(changes, "displayName", staff.displayName, v.displayName);
		diff(changes, "role", staff.role.name(), v.role.name());
		diff(changes, "active", staff.active, v.active);
		if (changes.isEmpty()) return ok(staff);

		staff.email = v.email;
		staff.displayName = v.displayName;
		staff.role = v.role;
		staff.active = v.active;
		staff.updatedBy = actor.email;
		Staff.flush();
		requireActiveAdminRemains();
		StaffEvent.record(staff, UPDATED, changes, actor);
		logger.infof("staff.updated staffId=%d actorId=%d fields=%s", staff.id, actor.id, changes.keySet());
		return ok(staff);
	}

	/** Forgets the linked JumpCloud subject, so the next sign-in by email links the current one. */
	@DELETE
	@Path("{id}/link")
	@Transactional
	public Response unlink(@RestPath long id, @HeaderParam(IF_MATCH) String ifMatch) {
		Staff.lockAccessChanges();
		Staff actor = requireAdmin();
		Staff staff = find(id);
		requireVersion(ifMatch, staff);
		if (isSelf(staff, actor)) throw new BusinessException(SELF_CHANGE);
		if (staff.jumpcloudSub == null) return ok(staff);

		staff.jumpcloudSub = null;
		staff.updatedBy = actor.email;
		Staff.flush();
		StaffEvent.record(staff, UNLINKED, null, actor);
		logger.infof("staff.unlinked staffId=%d actorId=%d", staff.id, actor.id);
		return ok(staff);
	}

	@DELETE
	@Path("{id}")
	@Transactional
	public Response delete(@RestPath long id, @HeaderParam(IF_MATCH) String ifMatch) {
		Staff.lockAccessChanges();
		Staff actor = requireAdmin();
		Staff staff = find(id);
		requireVersion(ifMatch, staff);
		if (isSelf(staff, actor)) throw new BusinessException(SELF_CHANGE);

		StaffEvent.record(staff, DELETED, StaffEvent.snapshot(staff), actor);
		staff.delete();
		Staff.flush();
		requireActiveAdminRemains();
		logger.infof("staff.deleted staffId=%d actorId=%d", id, actor.id);
		return Response.noContent().build();
	}

	private Staff requireAdmin() {
		if (actorSub == null || actorSub.isBlank()) throw new BusinessException(FORBIDDEN);
		return Staff.findBySub(actorSub).filter(Staff::isActiveAdmin)
				.orElseThrow(() -> new BusinessException(FORBIDDEN));
	}

	private static Staff find(long id) {
		return Staff.<Staff>findByIdOptional(id).orElseThrow(() -> new BusinessException(NOT_FOUND));
	}

	private static boolean isSelf(Staff staff, Staff actor) {
		return staff.id.equals(actor.id);
	}

	/**
	 * The self-change rule already keeps the actor an active ADMIN, so this can only fail if that
	 * rule is ever loosened. It guards the invariant itself rather than relying on that.
	 */
	private static void requireActiveAdminRemains() {
		if (Staff.countActiveAdmins() == 0) throw new BusinessException(LAST_ADMIN);
	}

	/** Accepts a strong ETag ({@code "3"}); anything else can't match the current version. */
	private static void requireVersion(String ifMatch, Staff staff) {
		if (ifMatch == null || ifMatch.isBlank()) throw new BusinessException(PRECONDITION_REQUIRED);
		if (!ifMatch.trim().equals("\"" + staff.version + "\"")) throw new BusinessException(STALE);
	}

	private static Response ok(Staff staff) {
		return Response.ok(View.of(staff)).tag(etag(staff)).build();
	}

	private static EntityTag etag(Staff staff) {
		return new EntityTag(Integer.toString(staff.version));
	}

	private static Valid validate(Input input) {
		if (input == null) throw new BusinessException(MALFORMED);
		Map<String, String> errors = new HashMap<>();

		String email = Staff.normalizeEmail(input.email());
		String emailError = Staff.validateEmail(email);
		if (emailError != null) errors.put("email", emailError);

		String displayName = input.displayName() == null ? null : input.displayName().trim();
		if (displayName != null && displayName.isEmpty()) displayName = null;
		if (displayName != null && displayName.length() > Staff.DISPLAY_NAME_MAX) errors.put("displayName", "too_long");

		Role role = null;
		if (input.role() == null || input.role().isBlank()) {
			errors.put("role", "required");
		} else {
			role = Role.parse(input.role()).orElse(null);
			if (role == null) errors.put("role", "invalid");
		}

		if (input.active() == null) errors.put("active", "required");

		if (!errors.isEmpty()) throw new BusinessException(INVALID, errors);
		return new Valid(email, displayName, role, input.active());
	}

	private static void diff(Map<String, Object> changes, String field, Object before, Object after) {
		if (!Objects.equals(before, after)) changes.put(field, Arrays.asList(before, after));
	}

	private static boolean parseBoolean(String value) {
		return switch (value) {
			case "true" -> true;
			case "false" -> false;
			default -> throw new BusinessException(MALFORMED);
		};
	}

	/** Escapes LIKE wildcards with {@code !}, the escape character the query declares. */
	static String escapeLike(String s) {
		return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}
}
