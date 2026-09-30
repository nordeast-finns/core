package com.finns.trident.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.finns.trident.core.BusinessException;
import com.finns.trident.core.ErrorCode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static com.finns.trident.core.ErrorCode.EMAIL_TAKEN;
import static com.finns.trident.core.ErrorCode.MALFORMED;
import static com.finns.trident.core.ErrorCode.NOT_FOUND;
import static com.finns.trident.core.ErrorCode.STALE;

/**
 * Turns every exception into RFC 9457 problem details carrying a stable {@code code}. Anything not
 * recognized is a server fault: logged, and returned as a bare 500 without internals.
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Exception> {
	private static final Logger logger = Logger.getLogger(GenericExceptionMapper.class);

	private static final String PROBLEM_JSON = "application/problem+json";

	@RegisterForReflection
	@JsonInclude(NON_EMPTY)
	public record Problem(String type, String title, int status, String code, Map<String, String> fields) {
	}

	@Override
	public Response toResponse(Exception exception) {
		for (Throwable t = exception; t != null; t = t.getCause()) {
			Response mapped = map(t);
			if (mapped != null) return mapped;
		}
		logger.error("Unmapped exception", exception);
		return Response.serverError().build();
	}

	private static Response map(Throwable t) {
		if (t instanceof BusinessException e) return problem(e.code, e.fields);
		if (t instanceof OptimisticLockException) return problem(STALE, Map.of());
		if (t instanceof ConstraintViolationException e && "staff_email_key".equals(e.getConstraintName())) {
			// Two creates (or an email change) racing for the same address.
			return problem(EMAIL_TAKEN, Map.of());
		}
		if (t instanceof JsonProcessingException) return problem(MALFORMED, Map.of());
		if (t instanceof WebApplicationException e) {
			int status = e.getResponse().getStatus();
			if (status == 404) return problem(NOT_FOUND, Map.of());
			if (status >= 400 && status < 500) return problem(status, MALFORMED.code, Map.of());
		}
		return null;
	}

	private static Response problem(ErrorCode code, Map<String, String> fields) {
		return problem(code.status, code.code, fields);
	}

	private static Response problem(int status, String code, Map<String, String> fields) {
		Response.Status known = Response.Status.fromStatusCode(status);
		// 422 isn't in the JAX-RS enum.
		String title = known != null ? known.getReasonPhrase() : status == 422 ? "Unprocessable Content" : "Error";
		return Response.status(status)
				.type(PROBLEM_JSON)
				.entity(new Problem("about:blank", title, status, code, fields))
				.build();
	}
}
