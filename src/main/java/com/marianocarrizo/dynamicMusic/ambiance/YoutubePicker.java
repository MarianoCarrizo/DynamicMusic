package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.service.YouTubeDownloader;
import com.marianocarrizo.dynamicMusic.service.YouTubeDownloader.SearchResult;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.function.Consumer;

public final class YoutubePicker {

    private YoutubePicker() {}

    public static void show(Stage owner, YouTubeDownloader downloader, Consumer<SearchResult> onPick) {
        Stage stage = new Stage();
        stage.setTitle("Browse YouTube");
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initOwner(owner);
        stage.setResizable(true);
        stage.setWidth(500);
        stage.setHeight(480);

        TextField txtQuery = new TextField();
        txtQuery.setPromptText("Search YouTube...");
        HBox.setHgrow(txtQuery, Priority.ALWAYS);

        Button btnSearch = new Button("Search");

        HBox searchRow = new HBox(8, txtQuery, btnSearch);
        searchRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label lblError = new Label();
        lblError.setVisible(false);
        lblError.setManaged(false);
        lblError.setWrapText(true);
        lblError.setStyle("-fx-text-fill: #e55; -fx-font-size: 11;");

        ProgressIndicator progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setManaged(false);
        progress.setMaxSize(24, 24);

        ListView<SearchResult> listView = new ListView<>();
        listView.setPrefHeight(350);
        VBox.setVgrow(listView, Priority.ALWAYS);
        listView.setCellFactory(lv -> new SearchResultCell());

        Button btnAdd = new Button("Add");
        btnAdd.setDisable(true);
        btnAdd.disableProperty().bind(
                listView.getSelectionModel().selectedItemProperty().isNull()
        );

        HBox addRow = new HBox(btnAdd);
        addRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, searchRow, lblError, progress, listView, addRow);
        root.setPadding(new Insets(10));

        stage.setScene(new Scene(root));
        stage.setOnShown(e -> txtQuery.requestFocus());

        Runnable doSearch = () -> {
            String query = txtQuery.getText().trim();
            if (query.isBlank()) return;

            lblError.setVisible(false);
            lblError.setManaged(false);
            progress.setVisible(true);
            progress.setManaged(true);
            btnSearch.setDisable(true);

            downloader.searchAsync(query, 10,
                    results -> {
                        listView.getItems().setAll(results);
                        progress.setVisible(false);
                        progress.setManaged(false);
                        btnSearch.setDisable(false);
                    },
                    error -> {
                        lblError.setText(error);
                        lblError.setVisible(true);
                        lblError.setManaged(true);
                        progress.setVisible(false);
                        progress.setManaged(false);
                        btnSearch.setDisable(false);
                    }
            );
        };

        btnSearch.setOnAction(e -> doSearch.run());
        txtQuery.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) doSearch.run();
        });

        Consumer<SearchResult> pick = result -> {
            stage.close();
            onPick.accept(result);
        };

        btnAdd.setOnAction(e -> {
            SearchResult selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) pick.accept(selected);
        });

        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                SearchResult selected = listView.getSelectionModel().getSelectedItem();
                if (selected != null) pick.accept(selected);
            }
        });

        stage.showAndWait();
    }

    private static final class SearchResultCell extends ListCell<SearchResult> {

        private final VBox   cellBox;
        private final Label  lblTitle;
        private final Label  lblMeta;

        SearchResultCell() {
            lblTitle = new Label();
            lblTitle.setWrapText(true);
            lblTitle.setStyle("-fx-font-weight: bold;");

            lblMeta = new Label();
            lblMeta.setStyle("-fx-font-size: 11; -fx-text-fill: #888;");

            cellBox = new VBox(2, lblTitle, lblMeta);
        }

        @Override
        protected void updateItem(SearchResult item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            lblTitle.setText(item.title());

            String meta = item.channel() != null && !item.channel().isBlank()
                    ? item.channel()
                    : "";
            String dur = item.durationLabel();
            if (!dur.isBlank()) {
                meta = meta.isBlank() ? dur : meta + " · " + dur;
            }
            lblMeta.setText(meta);

            setGraphic(cellBox);
        }
    }
}
