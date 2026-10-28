package io.github.itech_framework.api_client.handlers;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.annotations.parameters.Field;
import io.github.itech_framework.api_client.annotations.parameters.Part;
import io.github.itech_framework.api_client.utils.body.BodyType;
import io.github.itech_framework.api_client.utils.body.MultipartBody;
import io.github.itech_framework.core.exceptions.FrameworkException;

/**
 * Inspects the body-related parameters of one interface method once (at client creation, so mistakes fail
 * early) and builds the raw body value for each call.
 */
final class BodyResolver {

	record Resolved(Object value, BodyType type, String contentType) {
	}

	private record PartParam(int index, Part annotation) {
	}

	private record FieldParam(int index, Field annotation) {
	}

	private final int bodyIndex;
	private final Body bodyAnnotation;
	private final List<PartParam> partParams;
	private final List<FieldParam> fieldParams;

	BodyResolver(Method method) {
		Parameter[] parameters = method.getParameters();
		int bodyIdx = -1;
		Body bodyAnn = null;
		List<PartParam> parts = new ArrayList<>();
		List<FieldParam> fields = new ArrayList<>();

		for (int i = 0; i < parameters.length; i++) {
			Body body = parameters[i].getAnnotation(Body.class);
			Part part = parameters[i].getAnnotation(Part.class);
			Field field = parameters[i].getAnnotation(Field.class);
			if (body != null) {
				if (bodyAnn != null) {
					throw fail(method, "only one @Body parameter is allowed");
				}
				bodyIdx = i;
				bodyAnn = body;
			}
			if (part != null) {
				parts.add(new PartParam(i, part));
			}
			if (field != null) {
				fields.add(new FieldParam(i, field));
			}
		}
		if (bodyAnn != null && (!parts.isEmpty() || !fields.isEmpty())) {
			throw fail(method, "@Body cannot be combined with @Part or @Field");
		}
		if (!parts.isEmpty() && !fields.isEmpty()) {
			throw fail(method, "@Part (multipart) cannot be combined with @Field (form-urlencoded)");
		}
		this.bodyIndex = bodyIdx;
		this.bodyAnnotation = bodyAnn;
		this.partParams = parts;
		this.fieldParams = fields;
	}

	/** @return the body for this call, or null when the call has no body. */
	Resolved resolve(Object[] args) {
		if (bodyAnnotation != null) {
			Object value = args[bodyIndex];
			return value == null ? null : new Resolved(value, bodyAnnotation.type(), bodyAnnotation.contentType());
		}
		if (!partParams.isEmpty()) {
			MultipartBody multipart = MultipartBody.create();
			for (PartParam param : partParams) {
				Part ann = param.annotation();
				multipart.part(ann.value(), args[param.index()], ann.filename(), ann.contentType());
			}
			return multipart.isEmpty() ? null : new Resolved(multipart, BodyType.MULTIPART, null);
		}
		if (!fieldParams.isEmpty()) {
			Map<String, Object> form = new LinkedHashMap<>();
			for (FieldParam param : fieldParams) {
				Object value = args[param.index()];
				if (value != null) {
					form.put(param.annotation().value(), value);
				}
			}
			return form.isEmpty() ? null : new Resolved(form, BodyType.FORM, null);
		}
		return null;
	}

	private static FrameworkException fail(Method method, String message) {
		return new FrameworkException(method.getDeclaringClass().getName() + "." + method.getName() + ": " + message);
	}
}