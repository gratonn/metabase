import userEvent from "@testing-library/user-event";

import { setupGetUserKeyValueEndpoint } from "__support__/server-mocks/user-key-value";
import { createMockEntitiesState } from "__support__/store";
import {
  getIcon,
  queryIcon,
  renderWithProviders,
  screen,
  waitFor,
  within,
} from "__support__/ui";
import * as modelActions from "metabase/query_builder/actions/models";
import { MODAL_TYPES } from "metabase/query_builder/constants";
import { getMetadata } from "metabase/selectors/metadata";
import type Question from "metabase-lib/v1/Question";
import type { Card } from "metabase-types/api";
import {
  createMockCard,
  createMockNativeCard,
  createMockTable,
} from "metabase-types/api/mocks";
import { createSampleDatabase } from "metabase-types/api/mocks/presets";
import {
  createMockQueryBuilderState,
  createMockState,
} from "metabase-types/store/mocks";

import { QuestionActions } from "./QuestionActions";

const ICON_CASES_CARDS = [
  createMockCard({ name: "GUI" }),
  createMockNativeCard({ name: "SQL" }),
];

const ICON_CASES_LABELS = [
  {
    iconLabel: "bookmark icon",
    buttonLabel: "Bookmark",
  },
  {
    iconLabel: "info icon",
    buttonLabel: "More info",
  },
  {
    iconLabel: "ellipsis icon",
    buttonLabel: "Move, trash, and more…",
  },
];

const ICON_CASES = ICON_CASES_CARDS.flatMap((card) =>
  ICON_CASES_LABELS.map((labels) => ({ ...labels, card })),
);

interface SetupOpts {
  card: Card;
  hasDataPermissions?: boolean;
  hasAcknowledgedModelModal?: boolean;
}

function setup({
  card,
  hasDataPermissions = true,
  hasAcknowledgedModelModal = false,
}: SetupOpts) {
  setupGetUserKeyValueEndpoint({
    namespace: "user_acknowledgement",
    key: "turn_into_model_modal",
    value: hasAcknowledgedModelModal,
  });

  const state = createMockState({
    entities: createMockEntitiesState({
      databases: hasDataPermissions ? [createSampleDatabase()] : [],
      tables: [createMockTable({ id: `card__${card.id}` })],
      questions: [card],
    }),
    qb: createMockQueryBuilderState({
      card,
    }),
  });

  const metadata = getMetadata(state);
  const question = metadata.question(card.id) as Question;
  const onOpenModal = jest.fn();
  const onSetQueryBuilderMode = jest.fn();

  renderWithProviders(
    <QuestionActions
      question={question}
      isBookmarked={false}
      isShowingQuestionInfoSidebar={false}
      onOpenModal={onOpenModal}
      onToggleBookmark={jest.fn()}
      onSetQueryBuilderMode={onSetQueryBuilderMode}
      onInfoClick={jest.fn()}
    />,
    { storeInitialState: state },
  );

  return { onOpenModal, onSetQueryBuilderMode };
}

describe("QuestionActions", () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it.each(ICON_CASES)(
    `should display the "$iconLabel" icon with the "$buttonLabel" tooltip for $card.name questions`,
    async ({ iconLabel, buttonLabel, card }) => {
      setup({ card });

      const button = await screen.findByRole("button", { name: buttonLabel });
      expect(within(button).getByLabelText(iconLabel)).toBeInTheDocument();
      await userEvent.hover(button);
      const tooltip = await screen.findByRole("tooltip", { name: buttonLabel });
      expect(tooltip).toHaveTextContent(buttonLabel);
    },
  );

  describe("model query & metadata", () => {
    it("should allow to edit the model with write data & collection permissions", async () => {
      const { onSetQueryBuilderMode } = setup({
        card: createMockCard({
          type: "model",
          can_write: true,
        }),
      });

      await openActionsMenu();

      await userEvent.click(screen.getByText("Edit query definition"));
      await waitFor(() => {
        expect(onSetQueryBuilderMode).toHaveBeenCalledWith("dataset", {
          datasetEditorTab: "query",
        });
      });

      await openActionsMenu();

      await userEvent.click(screen.getByText("Edit metadata"));
      await waitFor(() => {
        expect(onSetQueryBuilderMode).toHaveBeenCalledWith("dataset", {
          datasetEditorTab: "metadata",
        });
      });
    });

    it("should not allow to edit the model without write collection permissions", async () => {
      setup({
        card: createMockCard({
          type: "model",
          can_write: false,
        }),
      });

      await openActionsMenu();

      expect(
        screen.queryByText("Edit query definition"),
      ).not.toBeInTheDocument();
      expect(screen.queryByText("Edit metadata")).not.toBeInTheDocument();
    });

    it("should allow to edit metadata but not the query without data permissions", async () => {
      setup({
        card: createMockCard({
          type: "model",
          can_write: true,
        }),
        hasDataPermissions: false,
      });

      await openActionsMenu();

      expect(
        screen.queryByText("Edit query definition"),
      ).not.toBeInTheDocument();
      expect(screen.getByText("Edit metadata")).toBeInTheDocument();
    });
  });

  describe("turning into a model or question", () => {
    it("should allow to turn into a model with write data & collection permissions", async () => {
      const { onOpenModal } = setup({
        card: createMockCard({
          type: "question",
          can_write: true,
        }),
      });

      await openActionsMenu();

      await userEvent.click(screen.getByText("Turn into a model"));
      expect(onOpenModal).toHaveBeenCalledWith(MODAL_TYPES.TURN_INTO_DATASET);
    });

    it("should skip showing model modal if user has acknowledged it previously", async () => {
      const turnQuestionIntoModelSpy = jest
        .spyOn(modelActions, "turnQuestionIntoModel")
        .mockImplementation(() => async () => {});

      const { onOpenModal } = setup({
        card: createMockCard({
          type: "question",
          can_write: true,
        }),
        hasAcknowledgedModelModal: true,
      });

      await openActionsMenu();

      await userEvent.click(screen.getByText("Turn into a model"));
      expect(onOpenModal).not.toHaveBeenCalled();

      // should still turn into a model
      expect(turnQuestionIntoModelSpy).toHaveBeenCalled();
      turnQuestionIntoModelSpy.mockRestore();
    });

    it("should allow to turn into a question with write data & collection permissions", async () => {
      const turnModelIntoQuestionSpy = jest
        .spyOn(modelActions, "turnModelIntoQuestion")
        .mockImplementation(() => () => Promise.resolve());
      setup({
        card: createMockCard({
          type: "model",
          can_write: true,
        }),
      });

      await openActionsMenu();

      await userEvent.click(screen.getByText("Turn back to saved question"));
      expect(turnModelIntoQuestionSpy).toHaveBeenCalledTimes(1);
    });

    it("should not allow to turn into a model without write collection permissions", async () => {
      setup({
        card: createMockCard({
          type: "question",
          can_write: false,
        }),
      });

      await openActionsMenu();

      expect(screen.queryByText("Turn int a model")).not.toBeInTheDocument();
    });

    it("should not allow to turn into a question without write collection permissions", async () => {
      setup({
        card: createMockCard({
          type: "model",
          can_write: false,
        }),
      });

      await openActionsMenu();

      expect(
        screen.queryByText("Turn back to saved question"),
      ).not.toBeInTheDocument();
    });

    it("should allow to turn into a model without data permissions", async () => {
      const { onOpenModal } = setup({
        card: createMockCard({
          type: "question",
          can_write: true,
        }),
        hasDataPermissions: false,
      });

      await openActionsMenu();

      await userEvent.click(screen.getByText("Turn into a model"));
      expect(onOpenModal).toHaveBeenCalledWith(MODAL_TYPES.TURN_INTO_DATASET);
    });

    it("should allow to turn into a question without data permissions", async () => {
      const turnModelIntoQuestionSpy = jest
        .spyOn(modelActions, "turnModelIntoQuestion")
        .mockImplementation(() => () => Promise.resolve());
      setup({
        card: createMockCard({
          type: "model",
          can_write: true,
        }),
        hasDataPermissions: false,
      });

      await openActionsMenu();

      await userEvent.click(screen.getByText("Turn back to saved question"));
      expect(turnModelIntoQuestionSpy).toHaveBeenCalledTimes(1);
    });
  });

  it("should not render the menu when there are no menu items", () => {
    setup({
      card: createMockCard({
        type: "model",
        can_write: false,
      }),
      hasDataPermissions: false,
    });

    expect(getIcon("info")).toBeInTheDocument();
    expect(queryIcon("ellipsis")).not.toBeInTheDocument();
  });
});

async function openActionsMenu() {
  await userEvent.click(getIcon("ellipsis"));
  expect(await screen.findByRole("menu")).toBeInTheDocument();
}
