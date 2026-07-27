package org.openremote.model.tracker;

import jakarta.persistence.Entity;

import java.util.Optional;
import static org.openremote.model.Constants.*;

import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

@Entity
public class TrackerAsset extends Asset<TrackerAsset> {

  // ── Device telemetry (permanent, written by Groovy rule) ─────────────────

  // Platform device identifier (LoRa devEUI), matched by the HTTP ingest endpoint
  public static final AttributeDescriptor<String> DEV_EUI_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("devEui", ValueType.TEXT);

  public static final AttributeDescriptor<ValueType.ObjectMap> RAW_VALUE_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("rawValue", ValueType.JSON_OBJECT,
      new MetaItem<>(MetaItemType.READ_ONLY),
      new MetaItem<>(MetaItemType.RULE_STATE));

  public static final AttributeDescriptor<Double> BATTERY_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("battery", ValueType.NUMBER,
      new MetaItem<>(MetaItemType.READ_ONLY),
      new MetaItem<>(MetaItemType.STORE_DATA_POINTS))
      .withUnits(UNITS_PERCENTAGE);

  public static final AttributeDescriptor<Long> LAST_MESSAGE_TIME_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("lastMessageTime", ValueType.TIMESTAMP,
      new MetaItem<>(MetaItemType.READ_ONLY));

  public static final AttributeDescriptor<Double> RSSI_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("rssi", ValueType.NUMBER,
      new MetaItem<>(MetaItemType.READ_ONLY))
      .withUnits("dBm");

  public static final AttributeDescriptor<Double> SNR_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("snr", ValueType.NUMBER,
      new MetaItem<>(MetaItemType.READ_ONLY))
      .withUnits(UNITS_DECIBEL);

  // ── Assignment (filled when the tracker is put in use, cleared when idle) ──

  public static final AttributeDescriptor<Boolean> ACTIVE_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("active", ValueType.BOOLEAN);

  public static final AttributeDescriptor<String> ASSIGNED_TO_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("assignedTo", ValueType.TEXT);

  // ── Asset descriptor (icon from OR icon set, teal colour) ─────────────────

  public static final AssetDescriptor<TrackerAsset> TRACKER_ASSET_DESCRIPTOR =
    new AssetDescriptor<>("map-marker", "009688", TrackerAsset.class);

  protected TrackerAsset() {
    // For JPA/Jackson
  }

  public TrackerAsset(String name) {
    super(name);
  }

  // ── Device telemetry getters ──────────────────────────────────────────────

  public Optional<String> getDevEui() {
    return getAttributes().getValue(DEV_EUI_ATTRIBUTE_DESCRIPTOR);
  }

  public Optional<ValueType.ObjectMap> getRawValue() {
    return getAttributes().getValue(RAW_VALUE_ATTRIBUTE_DESCRIPTOR);
  }

  public Optional<Double> getBattery() {
    return getAttributes().getValue(BATTERY_ATTRIBUTE_DESCRIPTOR);
  }

  public Optional<Long> getLastMessageTime() {
    return getAttributes().getValue(LAST_MESSAGE_TIME_ATTRIBUTE_DESCRIPTOR);
  }

  public Optional<Double> getRssi() {
    return getAttributes().getValue(RSSI_ATTRIBUTE_DESCRIPTOR);
  }

  public Optional<Double> getSnr() {
    return getAttributes().getValue(SNR_ATTRIBUTE_DESCRIPTOR);
  }

  // ── Assignment getters/setters ────────────────────────────────────────────

  public Optional<Boolean> isActive() {
    return getAttributes().getValue(ACTIVE_ATTRIBUTE_DESCRIPTOR);
  }

  public TrackerAsset setActive(boolean active) {
    getAttributes().getOrCreate(ACTIVE_ATTRIBUTE_DESCRIPTOR).setValue(active);
    return this;
  }

  public Optional<String> getAssignedTo() {
    return getAttributes().getValue(ASSIGNED_TO_ATTRIBUTE_DESCRIPTOR);
  }

  public TrackerAsset setAssignedTo(String assignedTo) {
    getAttributes().getOrCreate(ASSIGNED_TO_ATTRIBUTE_DESCRIPTOR).setValue(assignedTo);
    return this;
  }
}
