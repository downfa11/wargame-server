#pragma once
#include <iostream>
#include <WinSock2.h>
#include <Windows.h>
#include <process.h>
#include <ws2tcpip.h>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <stdlib.h>
#include <list>
#include<mutex>
#include <time.h>
#include <queue>
#include <string.h>
#include <iostream>
#include <locale>
#include <random>
#include <codecvt>
#include<thread>
#include <atomic>
#include <ctime>
#include <stack>
#include <iomanip>
#include<mysql.h>
#pragma comment(lib,"ws2_32.lib")
#pragma comment(lib,"libmysql.lib")
using namespace std;

typedef unsigned int       UINT;
typedef unsigned long       DWORD;
typedef unsigned short      WORD;
typedef unsigned char       BYTE;

#define BUF_SIZE 1024
#define READ 3
#define WRITE 5

#define H_CHAT 5877
#define H_START 1000
#define H_MOVESTART 8281
#define H_MOVE 8282
#define H_MOVESTOP 8283
#define H_TIMEOUT_SET 9425
#define H_NEWBI 1111
#define H_USER_DISCON 4444
#define H_CHANNEL_MOVE 1212
#define H_ROOM_MOVE 3434

#define H_CHAMPION_INIT 1240
#define H_CLIENT_STAT 1048
#define H_ATTACK_CLIENT 8888
#define H_ATTACK_STRUCT 8889
#define H_ATTACK_TARGET 1824

#define H_IS_READY 1214
#define H_TEAM 3493
#define H_BATTLE_START 1648


#define H_VICTORY 1934

#define H_STRUCTURE_CREATE 1924
#define H_STRUCTURE_DIE 1925
#define H_STRUCTURE_STAT 1926

#define H_GOTO_LOBBY 1334

#define H_CLIENT_DIE 1294
#define H_CLIENT_RESPAWN 1592
#define H_KILL_LOG 1818

#define H_CLIENT_STOP 4928
#define H_SEND_ME_AGAIN_CONNECT 9999

#define H_BUY_ITEM 2886
#define H_ITEM_STAT 9141

#define H_WELL 8313
#define H_NOTICE 4829

#define H_AUTHORIZATION 1525

typedef struct socketf
{
	SOCKET hClntSock;
	SOCKADDR_IN clntAdr;
} PER_HANDLE_DATA, * LPPER_HANDLE_DATA;

typedef struct OverlappedEx
{
	OVERLAPPED overlapped;
	WSABUF wsaBuf;
	int rwMode; //READ or WRITE
	BYTE* broken_data = new BYTE[1024];
	int broken_data_size = 0;
	bool header_recv = false;
	bool header_broken = false;
	bool data_broken = false;
} PER_IO_DATA, * LPPER_IO_DATA;



class Client
{
public:
	int socket = 0;
	int champindex = -1;
	string user_name = "";
	string user_code = "";

	time_t out_time = 0;
	int channel = 0;
	int room = 0;
	int kill = 0;
	int death = 0;
	int assist = 0;
	float x = 0;
	float y = 0;
	float z = 0;
	int elo = 1200;
	int gold = 1000;
	float rotationX = 0;
	float rotationY = 0;
	float rotationZ = 0;

	float level;
	int maxexp=100;
	int exp;

	bool isGame=false;
	bool stopped;
	bool attacked;

	int curhp;
	int maxhp;
	int curmana;
	int maxmana;
	int attack;
	int critical;
	int criProbability;
	float maxdelay;
	float curdelay;
	int attrange;
	float attspeed;
	float movespeed;

	int growhp;
	int growmana;
	int growAtt;
	int growCri;
	int growCriPro;

	int team = -1; // 0 for blue team, 1 for red team
	bool ready;
	
	vector<int> itemList{ 0,0,0,0,0,0 };


	stack<pair<int, float>> assistList;
	LPPER_HANDLE_DATA handle;
	LPPER_IO_DATA ioinfo;
};

#pragma pack(push,1)
struct ClientInfo
{
	int socket;
	int champindex;
	int gold;
	float x;
	float y;
	float z;
	int kill;
	int death;
	int assist;
	int level;
	int curhp;
	int maxhp;
	int curmana;
	int maxmana;
	int attack;
	int critical;
	int criProbability;
	float attspeed;
	int attrange;
	float movespeed;
};
#pragma pack(pop)

struct UserData {
	string user_code;
	string user_name;
	Client* user_client;
};

struct roomData {
	vector<UserData> user_data;
	int channel;
	int room;
};

#pragma pack(push,1)
struct ClientMovestart
{
	int socket;
	float rotationX;
	float rotationY;
	float rotationZ;
};
#pragma pack(pop)

#pragma pack(push,1)
struct Header
{
	int size;
	int number;
};
#pragma pack(pop)

class ChampionStats {
public:
	int index;
	string name;
	int attack;
	int maxhp;
	int maxmana;
	float movespeed;
	float maxdelay;
	float attspeed;
	int attrange;
	int critical;
	int criProbability;

	int growHp;
	int growMana;
	int growAtt;
	int growCri;
	int growCriPob;
};

struct mouseInfo
{
	float x;
	float y;
	float z;

};
#pragma pack(pop)


struct attinfo {
	int attacker;
	int attacked;
	int assist1=-1;
	int assist2 = -1;
	int assist3 = -1;
	int assist4 = -1;

};

class structure
{
public:
	int index = 0;
	float x = 0;
	float y = 0;
	float z = 0;
	int curhp;
	int maxhp;
	float maxdelay;
	float curdelay;
	int attrange;
	float bulletdmg;
	float bulletspeed;
	int team = -1; // 0 for blue team, 1 for red team
};

struct structureInfo
{
	int index;
	int curhp;
	int maxhp;
	float x;
	float y;
	float z;
	int attrange;
	float bulletdmg;
	float bulletspeed;
	int team;
};

struct bullet {
	float x;
	float y;
	float z;
	int dmg;
};

struct MatchResult {
	string datetime;
	int winTeam;
	vector<Client> participants;
	int gameDuration;
};

struct Item {
	int id;
	bool isPerchase;

};

struct itemStats {
	int id;
	string name;
	int gold;
	int attack;
	int maxhp;
	float movespeed;
	float maxdelay;
	float attspeed;
	int criProbability;
};

struct itemSlots {
	int socket;
	int id_0;
	int id_1;
	int id_2;
	int id_3;
	int id_4;
	int id_5;
};