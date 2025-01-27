package i5.las2peer.services.noracleService.model;

import java.io.Serial;

public class VotedQuestionRelation extends QuestionRelation {

	@Serial
	private static final long serialVersionUID = 937101316665902373L;

	private VoteList votes;

	public VotedQuestionRelation(QuestionRelation r) {
		this.setRelationId(r.getRelationId());
		this.setSpaceId(r.getSpaceId());
		this.setAuthorId(r.getAuthorId());
		this.setName(r.getName());
		this.setFirstQuestionId(r.getFirstQuestionId());
		this.setSecondQuestionId(r.getSecondQuestionId());
		this.setDirected(r.isDirected());
		this.setTimestampCreated(r.getTimestampCreated());
		this.setTimestampLastModified(r.getTimestampLastModified());
	}
	
	public VoteList getVotes() {
		return votes;
	}

	public void setVotes(VoteList votes) {
		this.votes = votes;
	}

}
