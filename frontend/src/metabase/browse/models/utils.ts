import { t } from "ttag";

import { getCollectionPathAsString } from "metabase/collections/utils";
import { entityForObject } from "metabase/lib/schema";
import type { IconName } from "metabase/ui";
import type { RecentItem, SearchResult } from "metabase-types/api";
import { SortDirection, type SortingOptions } from "metabase-types/api/sorting";

import type { ModelResult, RecentModel, SortColumn } from "./types";

export const isModel = (item: SearchResult) => item.model === "dataset";

export const isRecentModel = (item: RecentItem): item is RecentModel =>
  item.model === "dataset";

export const getModelDescription = (item: ModelResult) => {
  if (item.collection && !item.description?.trim()) {
    return t`A model`;
  } else {
    return item.description;
  }
};

const getValueForSorting = (
  model: ModelResult,
  sortColumn: SortColumn,
): string => {
  if (sortColumn === "collection") {
    return getCollectionPathAsString(model.collection) ?? "";
  } else {
    return model[sortColumn] ?? "";
  }
};

export const getSecondarySortColumn = (sortColumn: SortColumn): SortColumn => {
  return sortColumn === "name" ? "collection" : "name";
};

export function sortModels(
  models: ModelResult[],
  sortingOptions: SortingOptions<SortColumn>,
) {
  const { sort_column, sort_direction } = sortingOptions;

  const compare = (a: string, b: string) => a.localeCompare(b);

  return [...models].sort((modelA, modelB) => {
    const a = getValueForSorting(modelA, sort_column);
    const b = getValueForSorting(modelB, sort_column);

    let result = compare(a, b);
    if (result === 0) {
      const sort_column2 = getSecondarySortColumn(sort_column);
      const a2 = getValueForSorting(modelA, sort_column2);
      const b2 = getValueForSorting(modelB, sort_column2);
      result = compare(a2, b2);
    }

    return sort_direction === SortDirection.Asc ? result : -result;
  });
}

/** Find the maximum number of recently viewed models to show.
 * This is roughly proportional to the number of models the user
 * has permission to see */
export const getMaxRecentModelCount = (
  /** How many models the user has permission to see */
  modelCount: number,
) => {
  if (modelCount > 20) {
    return 8;
  }
  if (modelCount > 9) {
    return 4;
  }
  return 0;
};

export const getIcon = (item: unknown): { name: IconName; color: string } => {
  const entity = entityForObject(item);
  return entity?.objectSelectors?.getIcon?.(item) || { name: "folder" };
};
