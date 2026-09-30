package com.finns.trident.core;

import java.util.Map;

/** An expected failure, returned to the client as problem details with {@link ErrorCode#code}. */
public class BusinessException extends RuntimeException {
	public final ErrorCode code;

	/** Field name to field error code; only for {@link ErrorCode#INVALID}. */
	public final Map<String, String> fields;

	public BusinessException(ErrorCode code) {
		this(code, Map.of());
	}

	public BusinessException(ErrorCode code, Map<String, String> fields) {
		super(code.code, null, false, false);
		this.code = code;
		this.fields = Map.copyOf(fields);
	}
}
