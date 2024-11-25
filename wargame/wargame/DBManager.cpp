#include "DBManager.h"
#include "Resource.h"

DatabaseManager::DatabaseManager() {
    mysql_init(&mysql);
    if (!mysql_real_connect(&mysql, HOST, DB_USER, DB_PASSWORD, DB_NAME, PORT, NULL, 0)) {
        std::cerr << "Database connection error: " << mysql_error(&mysql) << std::endl;
    }
}

DatabaseManager::~DatabaseManager() {
    mysql_close(&mysql);
}

bool DatabaseManager::ExecuteQuery(const std::string& query, void (*processRow)(MYSQL_ROW)) {
    if (mysql_query(&mysql, query.c_str()) == 0) {
        MYSQL_RES* result = mysql_store_result(&mysql);
        if (result) {
            MYSQL_ROW row;
            while ((row = mysql_fetch_row(result))) {
                processRow(row);
            }
            mysql_free_result(result);
            return true;
        }
        else {
            std::cerr << "Failed to store result: " << mysql_error(&mysql) << std::endl;
        }
    }
    else {
        std::cerr << "Failed to execute query: " << mysql_error(&mysql) << std::endl;
    }
    return false;
}

void ChampionSystem::getChampionData(MYSQL_ROW row) {
    ChampionStats champion;
    champion.index = std::stoi(row[0]);
    champion.name = row[1];
    champion.maxhp = std::stoi(row[2]);
    champion.maxmana = std::stoi(row[3]);
    champion.attack = std::stoi(row[4]);
    champion.movespeed = std::stof(row[5]);
    champion.maxdelay = std::stof(row[6]);
    champion.attspeed = std::stof(row[7]);
    champion.attrange = std::stoi(row[8]);
    champion.critical = std::stof(row[9]);
    champion.criProbability = std::stof(row[10]);
    champion.growHp = std::stoi(row[11]);
    champion.growMana = std::stoi(row[12]);
    champion.growAtt = std::stoi(row[13]);
    champion.growCri = std::stoi(row[14]);
    champion.growCriPob = std::stoi(row[15]);
    champions.push_back(champion);
}

void ItemSystem::getItemData(MYSQL_ROW row) {
    itemStats item;
    item.id = std::stoi(row[0]);
    item.name = row[1];
    item.gold = std::stoi(row[2]);
    item.maxhp = std::stoi(row[3]);
    item.attack = std::stoi(row[4]);
    item.movespeed = std::stof(row[5]);
    item.maxdelay = std::stof(row[6]);
    item.attspeed = std::stof(row[7]);
    item.criProbability = std::stoi(row[8]);
    items.push_back(item);
}