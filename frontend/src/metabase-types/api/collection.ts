import type { ColorName } from "metabase/lib/colors/types";
import type { IconName, IconProps } from "metabase/ui";
import type {
  BaseEntityId,
  CollectionEssentials,
  Dashboard,
  DashboardId,
  PaginationRequest,
  PaginationResponse,
  VisualizationDisplay,
} from "metabase-types/api";

import type { CardId, CardType } from "./card";
import type { DatabaseId } from "./database";
import type { SortingOptions } from "./sorting";
import type { TableId } from "./table";
import type { UserId } from "./user";

export type RegularCollectionId = number;

export type CollectionId =
  | RegularCollectionId
  | "root"
  | "personal"
  | "users"
  | "trash";

export type CollectionContentModel = "card" | "dataset";

export type CollectionAuthorityLevel = "official" | null;

export type CollectionType = "instance-analytics" | "trash" | null;

export type LastEditInfo = {
  email: string;
  first_name: string;
  last_name: string;
  id: UserId;
  timestamp: string;
};

export type CollectionAuthorityLevelConfig = {
  type: CollectionAuthorityLevel;
  name: string;
  icon: IconName;
  color?: ColorName;
  tooltips?: Record<string, string>;
};

export type CollectionInstanceAnaltyicsConfig = {
  type: CollectionType;
  name: string;
  icon: IconName;
  color?: string;
  tooltips?: Record<string, string>;
};

export interface Collection {
  id: CollectionId;
  name: string;
  slug?: string;
  // "" for the default for EE's CUSTOM_INSTANCE_ANALYTICS_COLLECTION_ENTITY_ID
  entity_id?: BaseEntityId | "";
  description: string | null;
  can_write: boolean;
  can_restore: boolean;
  can_delete: boolean;
  archived: boolean;
  children?: Collection[];
  authority_level?: CollectionAuthorityLevel;
  type?: "instance-analytics" | "trash" | null;

  parent_id?: CollectionId | null;
  personal_owner_id?: UserId;
  is_personal?: boolean;
  is_sample?: boolean; // true if the collection part of the sample content

  location: string | null;
  effective_location?: string; // location path containing only those collections that the user has permission to access
  effective_ancestors?: CollectionEssentials[];

  here?: CollectionContentModel[];
  below?: CollectionContentModel[];

  // Assigned on FE
  originalName?: string;
  path?: CollectionId[];
}

export const COLLECTION_ITEM_MODELS = [
  "card",
  "dataset",
  "metric",
  "dashboard",
  "snippet",
  "collection",
  "indexed-entity",
] as const;
export type CollectionItemModel = (typeof COLLECTION_ITEM_MODELS)[number];

export type CollectionItemId = number;

export interface CollectionItem {
  id: CollectionItemId;
  entity_id?: BaseEntityId;
  model: CollectionItemModel;
  name: string;
  description: string | null;
  archived: boolean;
  copy?: boolean;
  collection_position?: number | null;
  collection_preview?: boolean | null;
  fully_parameterized?: boolean | null;
  based_on_upload?: TableId | null; // only for models
  collection?: Collection | null;
  collection_id: CollectionId | null; // parent collection id
  display?: VisualizationDisplay;
  personal_owner_id?: UserId;
  database_id?: DatabaseId;
  moderated_status?: string;
  type?: CollectionType | CardType;
  here?: CollectionItemModel[];
  below?: CollectionItemModel[];
  can_write?: boolean;
  can_restore?: boolean;
  can_delete?: boolean;
  "last-edit-info"?: LastEditInfo;
  location?: string;
  effective_location?: string;
  authority_level?: CollectionAuthorityLevel;
  dashboard_count?: number | null;
  getIcon: () => IconProps;
  getUrl: (opts?: Record<string, unknown>) => string;
  setArchived?: (
    isArchived: boolean,
    opts?: Record<string, unknown>,
  ) => Promise<void>;
  setPinned?: (isPinned: boolean) => void;
  setCollection?: (
    collection: Pick<Collection, "id"> | Pick<Dashboard, "id">,
  ) => void;
  setCollectionPreview?: (isEnabled: boolean) => void;
}

export interface CollectionListQuery {
  archived?: boolean;
  "exclude-other-user-collections"?: boolean;
  "exclude-archived"?: boolean;
  "personal-only"?: boolean;
  namespace?: string;
  tree?: boolean;
}

export type getCollectionRequest = {
  id: CollectionId;
  namespace?: "snippets";
};

export type ListCollectionItemsSortColumn =
  | "name"
  | "last_edited_at"
  | "last_edited_by"
  | "model";

export type ListCollectionItemsRequest = {
  id: CollectionId;
  models?: CollectionItemModel[];
  archived?: boolean;
  pinned_state?: "all" | "is_pinned" | "is_not_pinned";
  namespace?: "snippets";
} & PaginationRequest &
  Partial<SortingOptions<ListCollectionItemsSortColumn>>;

export type ListCollectionItemsResponse = {
  data: CollectionItem[];
  models: CollectionItemModel[] | null;
} & PaginationResponse;

export interface UpdateCollectionRequest {
  id: RegularCollectionId;
  name?: string;
  description?: string;
  archived?: boolean;
  parent_id?: RegularCollectionId | null;
  authority_level?: CollectionAuthorityLevel;
}

export interface CreateCollectionRequest {
  name: string;
  description?: string;
  parent_id?: CollectionId | null;
  namespace?: string;
  authority_level?: CollectionAuthorityLevel;
}

export interface ListCollectionsRequest {
  archived?: boolean;
  namespace?: string;
  "personal-only"?: boolean;
  "exclude-other-user-collections"?: boolean;
}
export interface ListCollectionsTreeRequest {
  "exclude-archived"?: boolean;
  "exclude-other-user-collections"?: boolean;
  namespace?: string;
  shallow?: boolean;
  "collection-id"?: RegularCollectionId | null;
}

export interface DeleteCollectionRequest {
  id: RegularCollectionId;
}

export interface DashboardQuestionCandidate {
  id: CardId;
  name: string;
  description: string | null;
  sole_dashboard_info: {
    id: DashboardId;
    name: string;
    description: string | null;
  };
}

export interface GetCollectionDashboardQuestionCandidatesRequest
  extends PaginationRequest {
  collectionId: CollectionId;
}

export interface GetCollectionDashboardQuestionCandidatesResult {
  total: number;
  data: DashboardQuestionCandidate[];
}

export interface MoveCollectionDashboardCandidatesRequest {
  collectionId: CollectionId;
  cardIds: CardId[];
}

export interface MoveCollectionDashboardCandidatesResult {
  moved: CardId[];
}
