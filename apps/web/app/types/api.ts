export interface SessionUser {
  id: string
  username: string
  displayName: string
  email: string
  role: 'MEMBER' | 'OWNER' | 'SUPER_ADMIN' | 'MODERATOR' | 'ANALYTICS_VIEWER' | 'SUPPORT_ADMIN'
  permissions: string[]
  mustChangePassword: boolean
}

export interface RoomSummary {
  id: string
  slug: string
  name: string
  description: string
  memberCount: number
  discussionCount: number
}

export interface DiscussionSummary {
  id: string
  slug: string
  title: string
  excerpt: string
  createdAt: string
  roomName: string
  roomSlug: string
  authorName: string
  authorUsername: string
  replyCount: number
  voteCount: number
}

export interface DiscussionDetail extends DiscussionSummary {
  body: string
  roomId: string
  authorId: string
  replies: Array<{
    id: string
    body: string
    authorName: string
    createdAt: string
  }>
}

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface TrendingNewsItem {
  headline: string
  publisher: string
  publishedAt: string
  articleUrl: string
  thumbnailUrl: string | null
}

export interface TrendingNewsResponse {
  status: 'AVAILABLE' | 'UNAVAILABLE'
  items: TrendingNewsItem[]
}

export interface TimePoint {
  date: string
  value: number
}

export interface AdminOverview {
  totalUsers: number
  newUsers: number
  activeUsers: number
  dau: number
  wau: number
  mau: number
  totalRooms: number
  totalDiscussions: number
  totalReplies: number
  pendingReports: number
  suspendedUsers: number
  from: string
  to: string
  refreshedAt: string
}

export interface AdminAnalytics {
  overview: AdminOverview
  registrations: TimePoint[]
  activeUsers: TimePoint[]
  engagement: TimePoint[]
}

export interface AdminUserRow {
  id: string
  username: string
  displayName: string
  email: string
  status: 'ACTIVE' | 'SUSPENDED'
  emailVerified: boolean
  createdAt: string
  lastActivityAt: string | null
  role: string
  roomCount: number
  discussionCount: number
  replyCount: number
}

export interface ReportRow {
  id: string
  targetType: string
  targetId: string
  reason: string
  details: string | null
  status: string
  createdAt: string
  reporterName: string
  targetSummary: string | null
}

export interface AuditRow {
  id: string
  actorName: string | null
  action: string
  targetType: string
  targetId: string | null
  outcome: string
  reason: string | null
  createdAt: string
}

export interface AdminRoomRow extends RoomSummary {
  active: boolean
  publicRoom: boolean
  createdAt: string
}

export interface AdminTeamResult {
  admins: Array<{ userId: string; displayName: string; email: string; username: string; role: string; active: boolean; createdAt: string; lastUsedAt: string | null }>
  invitations: Array<{ id: string; email: string; role: string; status: string; expiresAt: string; createdAt: string; inviterName: string }>
}
