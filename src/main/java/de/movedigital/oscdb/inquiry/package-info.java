/**
 * inquiry module: natural-language questions answered by model-generated SQL.
 * Reads tables of the feed, network and schedule modules only through the SQL views in schema
 * {@code inquiry_api} (V5__inquiry.sql); that coupling is SQL-only and invisible to Modulith.
 */
@org.springframework.modulith.ApplicationModule
package de.movedigital.oscdb.inquiry;
