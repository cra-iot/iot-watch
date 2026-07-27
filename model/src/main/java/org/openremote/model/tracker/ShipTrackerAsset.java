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
public class ShipTrackerAsset extends Asset<ShipTrackerAsset> {

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

  // ── Rental assignment (filled at checkout, cleared at return) ─────────────

  public static final AttributeDescriptor<Boolean> ACTIVE_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("active", ValueType.BOOLEAN);

  public static final AttributeDescriptor<String> GROUP_NAME_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("groupName", ValueType.TEXT);

  public static final AttributeDescriptor<String> BOAT_ID_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("boatId", ValueType.TEXT);

  public static final AttributeDescriptor<Long> START_TIME_ATTRIBUTE_DESCRIPTOR =
    new AttributeDescriptor<>("startTime", ValueType.TIMESTAMP);

  // ── Asset descriptor (icon from OR icon set, orange colour) ───────────────

  public static final AssetDescriptor<ShipTrackerAsset> SHIP_TRACKER_ASSET_DESCRIPTOR =
    new AssetDescriptor<>("map-marker-radius", "FF8C00", ShipTrackerAsset.class);

  protected ShipTrackerAsset() {
    // For JPA/Jackson
  }

  public ShipTrackerAsset(String name) {
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

  // ── Rental assignment getters/setters ─────────────────────────────────────

  public Optional<Boolean> isActive() {
    return getAttributes().getValue(ACTIVE_ATTRIBUTE_DESCRIPTOR);
  }

  public ShipTrackerAsset setActive(boolean active) {
    getAttributes().getOrCreate(ACTIVE_ATTRIBUTE_DESCRIPTOR).setValue(active);
    return this;
  }

  public Optional<String> getGroupName() {
    return getAttributes().getValue(GROUP_NAME_ATTRIBUTE_DESCRIPTOR);
  }

  public ShipTrackerAsset setGroupName(String groupName) {
    getAttributes().getOrCreate(GROUP_NAME_ATTRIBUTE_DESCRIPTOR).setValue(groupName);
    return this;
  }

  public Optional<String> getBoatId() {
    return getAttributes().getValue(BOAT_ID_ATTRIBUTE_DESCRIPTOR);
  }

  public ShipTrackerAsset setBoatId(String boatId) {
    getAttributes().getOrCreate(BOAT_ID_ATTRIBUTE_DESCRIPTOR).setValue(boatId);
    return this;
  }

  public Optional<Long> getStartTime() {
    return getAttributes().getValue(START_TIME_ATTRIBUTE_DESCRIPTOR);
  }

  public ShipTrackerAsset setStartTime(long epochMillis) {
    getAttributes().getOrCreate(START_TIME_ATTRIBUTE_DESCRIPTOR).setValue(epochMillis);
    return this;
  }
}
