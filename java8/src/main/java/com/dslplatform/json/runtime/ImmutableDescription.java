package com.dslplatform.json.runtime;

import com.dslplatform.json.JsonReader;
import com.dslplatform.json.JsonWriter;
import com.dslplatform.json.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;

public final class ImmutableDescription<T> extends WriteDescription<T> implements JsonReader.ReadObject<T> {

	private final Type manifest;
	private final Object[] defArgs;
	private final Settings.Function<Object[], T> newInstance;
	private final DecodePropertyInfo<JsonReader.ReadObject>[] decoders;
	private final boolean skipOnUnknown;
	private final boolean hasMandatory;
	private final long mandatoryFlag;
	private final String startError;
	private final String endError;

	public ImmutableDescription(
			final Class<T> manifest,
			final Object[] defArgs,
			final Settings.Function<Object[], T> newInstance,
			final JsonWriter.WriteObject[] encoders,
			final DecodePropertyInfo<JsonReader.ReadObject>[] decoders,
			final boolean alwaysSerialize,
			final boolean skipOnUnknown) {
		this((Type) manifest, defArgs, newInstance, encoders, decoders, alwaysSerialize, skipOnUnknown);
	}

	ImmutableDescription(
			final Type manifest,
			final Object[] defArgs,
			final Settings.Function<Object[], T> newInstance,
			final JsonWriter.WriteObject[] encoders,
			final DecodePropertyInfo<JsonReader.ReadObject>[] decoders,
			final boolean alwaysSerialize,
			final boolean skipOnUnknown) {
		super(encoders, alwaysSerialize);
		if (manifest == null) throw new IllegalArgumentException("manifest can't be null");
		if (defArgs == null) throw new IllegalArgumentException("defArgs can't be null");
		if (newInstance == null) throw new IllegalArgumentException("create can't be null");
		if (decoders == null) throw new IllegalArgumentException("decoders can't be null");
		this.manifest = manifest;
		this.defArgs = defArgs;
		this.newInstance = newInstance;
		this.decoders = DecodePropertyInfo.prepare(decoders);
		this.skipOnUnknown = skipOnUnknown;
		this.mandatoryFlag = DecodePropertyInfo.calculateMandatory(this.decoders);
		hasMandatory = mandatoryFlag != 0;
		this.startError = String.format("Expecting '{' to start decoding %s", manifest.getTypeName());
		this.endError = String.format("Expecting '}' or ',' while decoding %s", manifest.getTypeName());
	}

	@Nullable
	public T read(final JsonReader reader) throws IOException {
		if (reader.wasNull()) return null;
		else if (reader.last() != '{') {
			throw reader.newParseError(startError);
		}
		if (reader.getNextToken() == '}') {
			if (hasMandatory) {
				DecodePropertyInfo.showMandatoryError(reader, mandatoryFlag, decoders);
			}
			return newInstance.apply(defArgs);
		}
		final Object[] args = defArgs.clone();
		long currentMandatory = mandatoryFlag;
		int i = 0;
		while(i < decoders.length) {
			final DecodePropertyInfo<JsonReader.ReadObject> ri = decoders[i++];
			final int weakHash = reader.fillNameWeakHash();
			if (weakHash != ri.weakHash || !reader.wasLastName(ri.nameBytes)) {
				return readObjectSlow(args, reader, currentMandatory);
			}
			reader.getNextToken();
			if (ri.nonNull && reader.wasNull()) {
				throw reader.newParseErrorWith("Null value found for non-null attribute", ri.name);
			}
			args[ri.index] = ri.value.read(reader);
			currentMandatory = currentMandatory & ri.mandatoryValue;
			if (reader.getNextToken() == ',' && i != decoders.length) reader.getNextToken();
			else break;
		}
		return finalChecks(args, reader, currentMandatory);
	}

	@Nullable
	private T readObjectSlow(final Object[] args, final JsonReader reader, long currentMandatory) throws IOException {
		boolean processed = false;
		final int oldHash = reader.getLastHash();
		for (final DecodePropertyInfo<JsonReader.ReadObject> ri : decoders) {
			if (oldHash != ri.hash) continue;
			if (ri.exactName) {
				if (!reader.wasLastName(ri.nameBytes)) continue;
			}
			reader.getNextToken();
			if (ri.nonNull && reader.wasNull()) {
				throw reader.newParseErrorWith("Null value found for non-null attribute", ri.name);
			}
			args[ri.index] = ri.value.read(reader);
			currentMandatory = currentMandatory & ri.mandatoryValue;
			processed = true;
			break;
		}
		if (!processed) skip(reader);
		else reader.getNextToken();
		while (reader.last() == ','){
			reader.getNextToken();
			final int hash = reader.fillName();
			processed = false;
			for (final DecodePropertyInfo<JsonReader.ReadObject> ri : decoders) {
				if (hash != ri.hash) continue;
				if (ri.exactName) {
					if (!reader.wasLastName(ri.nameBytes)) continue;
				}
				reader.getNextToken();
				if (ri.nonNull && reader.wasNull()) {
					throw reader.newParseErrorWith("Null value found for non-null attribute", ri.name);
				}
				args[ri.index] = ri.value.read(reader);
				currentMandatory = currentMandatory & ri.mandatoryValue;
				processed = true;
				break;
			}
			if (!processed) skip(reader);
			else reader.getNextToken();
		}
		return finalChecks(args, reader, currentMandatory);
	}

	@Nullable
	private T finalChecks(Object[] args, JsonReader reader, long currentMandatory) throws IOException {
		if (reader.last() != '}') {
			if (reader.last() != ',') {
				throw reader.newParseError(endError);
			}
			reader.getNextToken();
			reader.fillNameWeakHash();
			return readObjectSlow(args, reader, currentMandatory);
		}
		if (hasMandatory && currentMandatory != 0) {
			DecodePropertyInfo.showMandatoryError(reader, currentMandatory, decoders);
		}
		return newInstance.apply(args);
	}

	private void skip(final JsonReader reader) throws IOException {
		if (!skipOnUnknown) {
			final String name = reader.getLastName();
			throw reader.newParseErrorFormat("Unknown property detected", name.length() + 3, "Unknown property detected: '%s' while reading %s", name, manifest.getTypeName());
		}
		reader.getNextToken();
		reader.skip();
	}
}
